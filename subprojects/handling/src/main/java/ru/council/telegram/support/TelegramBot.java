package ru.council.telegram.support;

import freemarker.template.TemplateException;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.council.telegram.support.annotations.Dispatcher;
import ru.council.telegram.support.exceptions.*;
import ru.council.telegram.support.models.OnUpdateProcessed;
import ru.council.telegram.support.reply.models.DeleteMessageResponse;
import ru.council.telegram.support.reply.models.EditMessageCaptionResponse;
import ru.council.telegram.support.reply.models.EditMessageInlineKeyboardResponse;
import ru.council.telegram.support.reply.models.UserResponse;

import javax.ws.rs.NotSupportedException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class TelegramBot extends TelegramLongPollingBot {

    protected final Map<String, DispatcherWrapper> executionMapping = new ConcurrentHashMap<>();
    private static final int CHAT_LOCK_STRIPES = 256;
    private final Object[] chatLocks = new Object[CHAT_LOCK_STRIPES];
    {
        Arrays.setAll(chatLocks, index -> new Object());
    }
    private final boolean deleteMessagesIfNotAllowed;
    private final boolean fallOnEditOrDelete;
    private volatile DispatcherWrapper mainDispatcher = null;

    public TelegramBot() {
        super();
        this.deleteMessagesIfNotAllowed = false;
        this.fallOnEditOrDelete = false;
    }

    public TelegramBot(DefaultBotOptions options) {
        super(options);
        this.deleteMessagesIfNotAllowed = false;
        this.fallOnEditOrDelete = false;
    }

    public TelegramBot(boolean deleteMessagesIfNotAllowed, boolean fallOnEditOrDelete) {
        this.deleteMessagesIfNotAllowed = deleteMessagesIfNotAllowed;
        this.fallOnEditOrDelete = fallOnEditOrDelete;
    }

    public TelegramBot(DefaultBotOptions options, boolean deleteMessagesIfNotAllowed, boolean fallOnEditOrDelete) {
        super(options);
        this.deleteMessagesIfNotAllowed = deleteMessagesIfNotAllowed;
        this.fallOnEditOrDelete = fallOnEditOrDelete;
    }

    public synchronized <T> void registerDispatcherAndItsMethods(T dispatcher, Class<T> itsClass) {
        if (dispatcher == null) {
            throw new DispatcherIsNullException("Dispatcher was null.");
        }
        if (itsClass == null) {
            throw new DispatcherClassIsNullException("Dispatcher class was null.");
        }
        Dispatcher annotation = itsClass.getAnnotation(Dispatcher.class);
        if (annotation == null) {
            throw new DispatcherNotAnnotatedException("Dispatcher must be annotated with @Dispatcher annotation.");
        }
        if (annotation.isMain()) {
            if (mainDispatcher != null) {
                throw new MultipleMainDispatchersException("Main dispatcher already set.");
            }
            mainDispatcher = new DispatcherWrapper(dispatcher, itsClass);
        } else {
            String canonicalName = itsClass.getCanonicalName();
            if (executionMapping.putIfAbsent(canonicalName, new DispatcherWrapper(dispatcher, itsClass)) != null) {
                throw new DispatcherAlreadyAddedToExecutionChainException("Dispatcher already added.");
            }
        }
    }

    /**
     * Backwards-compatible API. The state argument may already be stale if it was loaded
     * before another update. Prefer the Supplier overload for concurrent updates.
     */
    public OnUpdateProcessed onUpdateReceived(Update update, ApplicationState applicationState) {
        Objects.requireNonNull(applicationState, "applicationState");
        return onUpdateReceived(update, applicationState.getChatId(), () -> applicationState);
    }

    /**
     * Serializes the complete load/process/persist sequence for one chat within this
     * bot instance. The loader runs while holding the chat lock, so it must fetch
     * the latest committed state rather than returning a previously cached snapshot.
     * For multiple application instances, use transactional/optimistic storage locking.
     */
    public OnUpdateProcessed onUpdateReceived(Update update, long chatId, Supplier<ApplicationState> stateLoader) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(stateLoader, "stateLoader");
        int stripe = Math.floorMod(Long.hashCode(chatId), chatLocks.length);
        synchronized (chatLocks[stripe]) {
            ApplicationState current = Objects.requireNonNull(stateLoader.get(), "stateLoader returned null");
            if (current.getChatId() != chatId) {
                throw new IllegalArgumentException("Loaded state belongs to another chat");
            }
            return processUpdate(update, current);
        }
    }

    @SneakyThrows
    private OnUpdateProcessed processUpdate(Update update, ApplicationState applicationState) {
        String canonicalScreenClassName = applicationState.getCanonicalScreenClassName();
        DispatcherWrapper dispatcherWrapper = Objects.requireNonNull(executionMapping.get(canonicalScreenClassName), "No dispatcher registered for chat screen " + canonicalScreenClassName);
        Message message = update.getMessage();
        CallbackQuery callbackQuery = update.getCallbackQuery();
        ApplicationState copy = applicationState.copy();
        Object actions = null;
        if (message != null) {
            // Message was passed
            if (!applicationState.isMessagesAllowed()) {
                if (deleteMessagesIfNotAllowed) {
                    DeleteMessage deleteMessage = new DeleteMessage();
                    deleteMessage.setChatId(message.getChatId());
                    deleteMessage.setMessageId(message.getMessageId());
                    execute(deleteMessage);
                }
            } else {
                actions = dispatcherWrapper.onMessage(message, copy);
            }
        } else if (callbackQuery != null) {
            // Callback query is present
            actions = dispatcherWrapper.onCallbackQuery(callbackQuery, copy);
        } else {
            // Fallback method...
            if (dispatcherWrapper.hasFallbackMethod()) {
                actions = dispatcherWrapper.fallbackMethod(update, copy);
            } else {
                actions = fallbackMethod(update, copy);
            }
        }
        OnUpdateProcessed result = new OnUpdateProcessed();
        if (actions != null) {
            if (actions instanceof Class<?>) {
                String canonicalName = ((Class<?>) actions).getCanonicalName();
                DispatcherWrapper leadTo = executionMapping.get(canonicalName);
                Object actionsOnStart = leadTo.onStartInteraction(update, copy);
                Object actionsOnStop = dispatcherWrapper.onStopInteraction(update, copy);
                if (actionsOnStart != null) {
                    result.append(processActionsObject(actionsOnStart, copy.getChatId()));
                }
                if (actionsOnStop != null) {
                    result.append(processActionsObject(actionsOnStop, copy.getChatId()));
                }
            } else {
                result = processActionsObject(actions, copy.getChatId());
            }
        }
        if (!Objects.equals(applicationState, copy)) {
            persistState(copy);
        }
        return result;
    }

    protected OnUpdateProcessed processActionsObject(@NonNull Object actions, long chatId) throws TelegramApiException, TemplateException, IOException {
        if (actions instanceof List) {
            OnUpdateProcessed onUpdateProcessed = new OnUpdateProcessed();
            List<?> givenActions = (List<?>) actions;
            for (Object action : givenActions) {
                if (action != null) {
                    onUpdateProcessed.append(processActionsObject(action, chatId));
                }
            }
            return onUpdateProcessed;
        } else {
            if (actions instanceof DeleteMessageResponse) {
                OnUpdateProcessed onUpdateProcessed = new OnUpdateProcessed();
                try {
                    Boolean executed = execute(((DeleteMessageResponse) actions).toTelegramApiModel());
                    Integer messageId = ((DeleteMessageResponse) actions).getMessageId();
                    if (executed) {
                        onUpdateProcessed.getDeletedMessages().add(messageId);
                    } else {
                        onUpdateProcessed.getCouldNotDeleteMessages().add(messageId);
                    }
                } catch (Exception e) {
                    if (fallOnEditOrDelete) {
                        throw e;
                    }
                    onUpdateProcessed.getCouldNotDeleteMessages().add(((DeleteMessageResponse) actions).getMessageId());
                }
                return onUpdateProcessed;
            } else if (actions instanceof EditMessageInlineKeyboardResponse) {
                OnUpdateProcessed onUpdateProcessed = new OnUpdateProcessed();
                try {
                    onUpdateProcessed.getEditedMessages().add(((EditMessageInlineKeyboardResponse) actions).process(this).getMessageId());
                } catch (Exception e) {
                    if (fallOnEditOrDelete) {
                        throw e;
                    }
                }
                return onUpdateProcessed;
            } else if (actions instanceof UserResponse) {
                return processUserResponseModel((UserResponse) actions, chatId);
            } else {
                throw new NotSupportedException("Action entity of type " + actions.getClass() + " is not supported.");
            }
        }
    }

    protected OnUpdateProcessed processUserResponseModel(UserResponse userResponse, long chatId) throws TelegramApiException, TemplateException, IOException {
        OnUpdateProcessed onUpdateProcessed = new OnUpdateProcessed();
        List<Message> process = userResponse.process(this, chatId);
        onUpdateProcessed.getSentMessages().addAll(process);
        return onUpdateProcessed;
    }

    protected abstract Object fallbackMethod(Update update, ApplicationState state);

    protected abstract void persistState(ApplicationState state);

}
