package org.example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        // Укажи свои данные здесь


        try {
            // Инициализация API
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

            // Регистрация бота
            botsApi.registerBot(new SenimWikiBot(botToken, adminChatId));

            System.out.println("Бот успешно запущен!");
        } catch (TelegramApiException e) {
            System.err.println("Ошибка при запуске бота: " + e.getMessage());
            e.printStackTrace();
        }
    }
}