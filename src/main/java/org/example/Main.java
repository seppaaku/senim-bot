package org.example;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        // Читаем переменные из Railway (Environment Variables)
        String botToken = System.getenv("BOT_TOKEN");
        String adminChatId = System.getenv("ADMIN_CHAT_ID");

        // Проверка — чтобы сразу видеть ошибку если переменные не заданы
        if (botToken == null || botToken.isEmpty()) {
            System.err.println("Ошибка: BOT_TOKEN не задан!");
            return;
        }
        if (adminChatId == null || adminChatId.isEmpty()) {
            System.err.println("Ошибка: ADMIN_CHAT_ID не задан!");
            return;
        }

        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new SenimWikiBot(botToken, adminChatId));
            System.out.println("Бот успешно запущен!");
        } catch (TelegramApiException e) {
            System.err.println("Ошибка при запуске бота: " + e.getMessage());
            e.printStackTrace();
        }
    }
}