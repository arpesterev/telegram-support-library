package ru.council.telegram.support;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.council.telegram.support.annotations.Dispatcher;
import ru.council.telegram.support.annotations.OnTextMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentUpdateTest {

    @Dispatcher
    public static class CounterDispatcher {
        @OnTextMessage(id = 1)
        public void increment(Message message, String text, ApplicationState state) {
            int previous = (int) state.getAdditionalParameters().getOrDefault("count", 0);
            state.getAdditionalParameters().put("count", previous + 1);
        }
    }

    private static class LocalBot extends TelegramBot {
        private final Map<Long, ApplicationState> stored = new ConcurrentHashMap<>();

        @Override
        public void onUpdateReceived(Update update) {
            // Telegram API callback is not exercised by this test.
        }

        @Override
        public String getBotUsername() {
            return "concurrency-test";
        }

        @Override
        public String getBotToken() {
            return "not-a-real-token";
        }

        @Override
        protected Object fallbackMethod(Update update, ApplicationState state) {
            return null;
        }

        @Override
        protected void persistState(ApplicationState state) {
            stored.put(state.getChatId(), state.copy());
        }
    }

    private Update incrementMessage() {
        Message message = new Message();
        message.setText("1");
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    @Test
    void parallelUpdatesOfSameChatDoNotLoseState() throws Exception {
        LocalBot bot = new LocalBot();
        bot.registerDispatcherAndItsMethods(new CounterDispatcher(), CounterDispatcher.class);
        long chatId = 100L;
        bot.stored.put(chatId,
                new ApplicationState(CounterDispatcher.class.getCanonicalName(), "counter", true, chatId));

        int requests = 120;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    bot.onUpdateReceived(incrementMessage(), chatId,
                            () -> bot.stored.get(chatId).copy());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
            assertEquals(requests, bot.stored.get(chatId).getAdditionalParameters().get("count"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stateEqualityUsesObjectEquals() {
        ApplicationState left = new ApplicationState("screen", "method", true, 9);
        ApplicationState right = left.copy();
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        right.setMethodName("changed");
        assertNotEquals(left, right);
    }

    @Test
    void differentChatStateFromLoaderIsRejected() {
        LocalBot bot = new LocalBot();
        assertThrows(IllegalArgumentException.class,
                () -> bot.onUpdateReceived(incrementMessage(), 1,
                        () -> new ApplicationState("screen", "method", true, 2)));
    }
}
