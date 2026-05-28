package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

public class SenimWikiBot extends TelegramLongPollingBot {

    private final String botToken;
    private final String adminChatId;

    private static final String API_BASE = "https://senim-backend-production.up.railway.app";

    // --- Состояния пользователей ---
    private final Map<Long, String> userState      = new HashMap<>();
    private final Map<Long, Report> pendingReports = new HashMap<>();
    private final Set<Long>         awaitingScan   = new HashSet<>();
    private final Map<Long, String> userLang       = new HashMap<>();

    // --- История состояний для пошагового отката ---
    private final Map<Long, Deque<String>> stateHistory = new HashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ─────────────────────────────────────────────
    // TRANSLATIONS
    // ─────────────────────────────────────────────
    private String t(long userId, String key) {
        String lang = userLang.getOrDefault(userId, "kz");
        return TRANSLATIONS.getOrDefault(lang + "." + key, key);
    }

    private static final Map<String, String> TRANSLATIONS = new HashMap<>() {{

        // WELCOME
        put("kz.welcome", """
                🛡 *SenimWiki ботқа қош келдіңіз!*

                ✅ Не істей аласыз:
                📲 /scan — мәтінді алаяқтыққа тексеру
                🖼 Сурет жіберу — скриншотты тексеру (OCR + AI)
                📝 /report — күдікті ұйым туралы хабарлау
                ❓ /help — анықтама

                🌐 senim-wiki.vercel.app
                """);
        put("ru.welcome", """
                🛡 *Добро пожаловать в бот SenimWiki!*

                ✅ Что умеет бот:
                📲 /scan — проверить текст на мошенничество
                🖼 Отправить фото — проверить скриншот (OCR + AI)
                📝 /report — сообщить о подозрительной организации
                ❓ /help — справка

                🌐 senim-wiki.vercel.app
                """);

        // LANGUAGE SELECT
        put("kz.choose_lang", "🌐 Тілді таңдаңыз / Выберите язык:");
        put("ru.choose_lang", "🌐 Тілді таңдаңыз / Выберите язык:");

        // HELP
        put("kz.help", """
                ℹ️ *Анықтама*

                /scan — мәтін арқылы AI тексеру
                🖼 *Сурет жіберу* — скриншотты тексеру
                /report — күдікті ұйымды хабарлау
                /lang — тілді өзгерту
                /cancel — ағымдағы әрекетті болдырмау

                📧 info@senimwiki.kz
                """);
        put("ru.help", """
                ℹ️ *Справка*

                /scan — запустить текст для проверки AI
                🖼 *Отправить фото* — проверить скриншот
                /report — подать жалобу на организацию
                /lang — сменить язык
                /cancel — отменить текущее действие

                📧 info@senimwiki.kz
                """);

        // SCAN
        put("kz.scan_prompt",    "🔍 *Мәтінді енгізіңіз*\n\nТексергіңіз келген мәтінді жіберіңіз.\nБолдырмау үшін /cancel.");
        put("ru.scan_prompt",    "🔍 *Введите текст для проверки*\n\nОтправьте любой подозрительный текст.\nДля отмены — /cancel.");
        put("kz.scan_wait",      "⏳ Жасанды интеллект мәтінді тексеруде…");
        put("ru.scan_wait",      "⏳ Искусственный интеллект анализирует текст…");
        put("kz.photo_received", "📷 Сурет қабылданды. AI тексеруде (OCR + талдау)…");
        put("ru.photo_received", "📷 Фото принято. AI анализирует (OCR + проверка)…");

        // SCAN RESULT
        put("kz.result_title",   "📊 *Тексеру нәтижесі*");
        put("ru.result_title",   "📊 *Результат проверки*");
        put("kz.result_score",   "🎯 *Қауіп деңгейі:*");
        put("ru.result_score",   "🎯 *Уровень угрозы:*");
        put("kz.result_safe",    "✅ Мәтін қауіпсіз болып көрінеді");
        put("ru.result_safe",    "✅ Текст выглядит безопасным");
        put("kz.result_danger",  "🚨 Алаяқтық белгілері анықталды!");
        put("ru.result_danger",  "🚨 Обнаружены признаки мошенничества!");
        put("kz.result_hits",    "⚠️ *Анықталған белгілер:*");
        put("ru.result_hits",    "⚠️ *Обнаруженные признаки:*");
        put("kz.result_no_hits", "ℹ️ Күдікті белгілер табылмады.");
        put("ru.result_no_hits", "ℹ️ Подозрительных признаков не найдено.");
        put("kz.result_ocr",     "📄 *OCR мәтіні:*");
        put("ru.result_ocr",     "📄 *Распознанный текст:*");

        // EMERGENCY
        put("kz.emergency", """
                🆘 *Шұғыл байланыс:*
                📞 Алаяқтықты хабарлау телефоны: *1477*
                📞 Полицияға хабарлау: *102*
                🌐 Онлайн өтініш: [e-Otinish](https://egov.kz/cms/ru/services/e_app)
                """);
        put("ru.emergency", """
                🆘 *Экстренные контакты:*
                📞 Горячая линия по мошенничеству: *1477*
                📞 Обратиться в полицию: *102*
                🌐 Онлайн заявление: [e-Otinish](https://egov.kz/cms/ru/services/e_app)
                """);

        // REPORT
        put("kz.report_type",    "📋 Хабарлама *түрін* таңдаңыз:");
        put("ru.report_type",    "📋 Выберите *тип* сообщения:");
        put("kz.report_name",    "🏢 Ұйымның немесе адамның *атауын* жазыңыз:");
        put("ru.report_name",    "🏢 Введите *название* организации или имя человека:");
        put("kz.report_desc",    "📝 *Мәселені сипаттаңыз:*\n\nНені байқадыңыз — соны жазыңыз.");
        put("ru.report_desc",    "📝 Опишите *проблему*:\n\nНапишите, что именно вы заметили.");
        put("kz.report_contact", "📞 Байланыс ақпаратын жіберіңіз (телефон, email) немесе `-` жіберіңіз.");
        put("ru.report_contact", "📞 Укажите контактные данные (телефон, email) или отправьте `-` чтобы пропустить.");
        put("kz.report_done",    "✅ *Хабарлама қабылданды!*\n\nРахмет! SenimWiki командасы қарастырады.\n\n🌐 senim-wiki.vercel.app");
        put("ru.report_done",    "✅ *Жалоба принята!*\n\nСпасибо! Команда SenimWiki рассмотрит её.\n\n🌐 senim-wiki.vercel.app");

        // REPORT TYPE BUTTONS
        put("kz.type_fraud",  "💰 Алаяқтық");
        put("ru.type_fraud",  "💰 Мошенничество");
        put("kz.type_org",    "🏛 Күдікті ұйым");
        put("ru.type_org",    "🏛 Подозрительная организация");
        put("kz.type_other",  "❓ Басқа");
        put("ru.type_other",  "❓ Другое");

        // CANCEL / BACK / ERRORS
        put("kz.cancelled",       "❌ Болдырылмады. /scan немесе /report командасын пайдаланыңыз.");
        put("ru.cancelled",       "❌ Отменено. Используйте /scan или /report.");
        put("kz.back_to_type",    "↩️ Хабарлама түрін қайта таңдаңыз:");
        put("ru.back_to_type",    "↩️ Выберите тип сообщения снова:");
        put("kz.back_to_name",    "↩️ Ұйымның атауын қайта жазыңыз:");
        put("ru.back_to_name",    "↩️ Введите название организации снова:");
        put("kz.back_to_desc",    "↩️ Мәселені қайта сипаттаңыз:");
        put("ru.back_to_desc",    "↩️ Опишите проблему снова:");
        put("kz.unknown_cmd",     "❓ Белгісіз команда. /help командасын пайдаланыңыз.");
        put("ru.unknown_cmd",     "❓ Неизвестная команда. Используйте /help.");
        put("kz.server_error",    "❌ Сервер қатесі: ");
        put("ru.server_error",    "❌ Ошибка сервера: ");
        put("kz.parse_error",     "⚠️ Жауапты өңдеу мүмкін болмады.");
        put("ru.parse_error",     "⚠️ Не удалось обработать ответ.");
        put("kz.lang_changed",    "✅ Тіл қазақшаға ауыстырылды.");
        put("ru.lang_changed",    "✅ Язык переключён на русский.");
    }};

    // ─────────────────────────────────────────────
    // CONSTRUCTORS & BOT INFO
    // ─────────────────────────────────────────────
    public SenimWikiBot(String botToken, String adminChatId) {
        this.botToken    = botToken;
        this.adminChatId = adminChatId;
    }

    @Override public String getBotUsername() { return "senimwiki_bot"; }
    @Override public String getBotToken()    { return botToken; }

    // ─────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message msg    = update.getMessage();
            long    chatId = msg.getChatId();
            long    userId = msg.getFrom().getId();

            if (msg.hasPhoto()) {
                handlePhotoScan(chatId, userId, msg);
                return;
            }
            if (msg.hasText()) {
                handleTextMessage(update);
            }

        } else if (update.hasCallbackQuery()) {
            handleCallback(update);
        }
    }

    // ─────────────────────────────────────────────
    // TEXT ROUTING
    // ─────────────────────────────────────────────
    private void handleTextMessage(Update update) {
        long   chatId   = update.getMessage().getChatId();
        long   userId   = update.getMessage().getFrom().getId();
        String text     = update.getMessage().getText();
        String username = update.getMessage().getFrom().getUserName();
        String state    = userState.getOrDefault(userId, "IDLE");

        // Язык ещё не выбран
        if (!userLang.containsKey(userId) && !text.equals("/start")) {
            sendLangSelect(chatId, userId);
            return;
        }

        // Общие команды имеют приоритет над любым состоянием
        switch (text) {
            case "/start"  -> { if (!userLang.containsKey(userId)) sendLangSelect(chatId, userId); else sendWelcome(chatId, userId); return; }
            case "/scan"   -> { startScanMode(chatId, userId); return; }
            case "/report" -> { startReport(chatId, userId, username); return; }
            case "/lang"   -> { sendLangSelect(chatId, userId); return; }
            case "/cancel" -> { cancelAll(chatId, userId); return; }
            case "/help"   -> { send(chatId, t(userId, "help"), true); return; }
        }

        // Кнопка «Назад» в reply-клавиатуре
        if (text.equals("← " + t(userId, "btn_back")) || text.equals("←")) {
            handleBack(chatId, userId);
            return;
        }

        // Ожидаем ввод текста для сканирования
        if (awaitingScan.contains(userId)) {
            awaitingScan.remove(userId);
            scanText(chatId, userId, text);
            return;
        }

        // Шаги репорта
        handleStateInput(chatId, userId, text, state, username);
    }

    // ─────────────────────────────────────────────
    // REPLY KEYBOARD (главное меню)
    // ─────────────────────────────────────────────

    /** Постоянная клавиатура с основными командами */
    private ReplyKeyboardMarkup buildMainKeyboard(long userId) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("📲 /scan"));
        row1.add(new KeyboardButton("📝 /report"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("❓ /help"));
        row2.add(new KeyboardButton("🌐 /lang"));

        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setKeyboard(List.of(row1, row2));
        kb.setResizeKeyboard(true);
        kb.setOneTimeKeyboard(false);
        return kb;
    }

    /** Клавиатура во время ввода данных репорта (с кнопкой Назад и Отмена) */
    private ReplyKeyboardMarkup buildNavKeyboard(long userId) {
        KeyboardRow row = new KeyboardRow();
        row.add(new KeyboardButton("⬅️ Назад / Артқа"));
        row.add(new KeyboardButton("❌ /cancel"));

        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup();
        kb.setKeyboard(List.of(row));
        kb.setResizeKeyboard(true);
        kb.setOneTimeKeyboard(false);
        return kb;
    }

    /** Скрыть reply-клавиатуру */
    private ReplyKeyboardRemove removeKeyboard() {
        ReplyKeyboardRemove remove = new ReplyKeyboardRemove();
        remove.setRemoveKeyboard(true);
        return remove;
    }

    // ─────────────────────────────────────────────
    // LANGUAGE SELECT
    // ─────────────────────────────────────────────
    private void sendLangSelect(long chatId, long userId) {
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder().text("🇰🇿 Қазақша").callbackData("LANG_kz").build(),
                        InlineKeyboardButton.builder().text("🇷🇺 Русский").callbackData("LANG_ru").build()
                ))
                .build();

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText("🌐 Тілді таңдаңыз / Выберите язык:");
        msg.setReplyMarkup(kb);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ─────────────────────────────────────────────
    // WELCOME
    // ─────────────────────────────────────────────
    private void sendWelcome(long chatId, long userId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(t(userId, "welcome"));
        msg.setParseMode("Markdown");
        msg.disableWebPagePreview();
        msg.setReplyMarkup(buildMainKeyboard(userId)); // показываем главную клавиатуру
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ─────────────────────────────────────────────
    // SCAN: TEXT
    // ─────────────────────────────────────────────
    private void startScanMode(long chatId, long userId) {
        clearReportState(userId);
        awaitingScan.add(userId);
        send(chatId, t(userId, "scan_prompt"), true, removeKeyboard());
    }

    private void scanText(long chatId, long userId, String text) {
        send(chatId, t(userId, "scan_wait"));
        new Thread(() -> {
            try {
                String body = "{\"text\":" + jsonEscape(text) + "}";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/api/scan/text"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                sendScanResult(chatId, userId, resp.body(), false);
                // После результата — вернуть главную клавиатуру
                sendMainMenu(chatId, userId);
            } catch (Exception e) {
                send(chatId, t(userId, "server_error") + e.getMessage());
                sendMainMenu(chatId, userId);
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    // SCAN: IMAGE (OCR)
    // ─────────────────────────────────────────────
    private void handlePhotoScan(long chatId, long userId, Message msg) {
        if (!userLang.containsKey(userId)) { sendLangSelect(chatId, userId); return; }
        send(chatId, t(userId, "photo_received"));

        new Thread(() -> {
            try {
                List<PhotoSize> photos = msg.getPhoto();
                PhotoSize best = photos.stream()
                        .max(Comparator.comparingInt(PhotoSize::getFileSize))
                        .orElseThrow();

                GetFile getFile = new GetFile();
                getFile.setFileId(best.getFileId());
                org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
                String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + tgFile.getFilePath();
                byte[] imageBytes = URI.create(fileUrl).toURL().openStream().readAllBytes();

                String boundary = "----Boundary" + System.currentTimeMillis();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"screenshot.jpg\"\r\n");
                dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                dos.write(imageBytes);
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
                dos.flush();

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/api/scan/image"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                sendScanResult(chatId, userId, resp.body(), true);
                sendMainMenu(chatId, userId);
            } catch (Exception e) {
                send(chatId, t(userId, "server_error") + e.getMessage());
                sendMainMenu(chatId, userId);
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    // FORMAT & SEND SCAN RESULT
    // ─────────────────────────────────────────────
    private void sendScanResult(long chatId, long userId, String jsonBody, boolean isImage) {
        try {
            int     score     = parseJsonInt(jsonBody,  "score");
            boolean safe      = parseJsonBool(jsonBody, "safe");
            String  hits      = parseJsonStringArray(jsonBody, "hits");
            String  extracted = isImage ? parseJsonString(jsonBody, "extractedText") : null;

            String verdict = safe ? t(userId, "result_safe") : t(userId, "result_danger");

            StringBuilder sb = new StringBuilder();
            sb.append(t(userId, "result_title")).append("\n\n");
            sb.append(t(userId, "result_score")).append(" `").append(score).append("%`\n");
            sb.append("➤ ").append(verdict).append("\n");

            if (hits != null && !hits.isBlank())
                sb.append(t(userId, "result_hits")).append(" ").append(hits).append("\n");
            else
                sb.append(t(userId, "result_no_hits")).append("\n");

            sb.append("\n");
            if (isImage) sb.append("🔎 _Llama 4 Scout OCR_ + ");
            sb.append("🤖 _Llama 3.3 70b_");

            if (score >= 75)
                sb.append("\n\n").append(t(userId, "emergency"));

            if (extracted != null && !extracted.isBlank()) {
                String preview = extracted.length() > 300 ? extracted.substring(0, 300) + "…" : extracted;
                sb.append("\n\n").append(t(userId, "result_ocr")).append("\n`").append(preview).append("`");
            }

            send(chatId, sb.toString(), true);
        } catch (Exception e) {
            send(chatId, t(userId, "parse_error") + "\n`" + jsonBody.substring(0, Math.min(200, jsonBody.length())) + "`", true);
        }
    }

    // ─────────────────────────────────────────────
    // REPORT FLOW
    // ─────────────────────────────────────────────
    private void startReport(long chatId, long userId, String username) {
        clearReportState(userId);
        pendingReports.put(userId, new Report(userId, username));
        pushState(userId, "AWAIT_TYPE");

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(t(userId, "report_type"));
        msg.setParseMode("Markdown");
        msg.setReplyMarkup(buildTypeKeyboard(userId));
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    /**
     * Обработка текстового ввода на каждом шаге репорта.
     * После каждого шага добавляем кнопку «Назад» через nav-клавиатуру.
     */
    private void handleStateInput(long chatId, long userId, String text, String state, String username) {
        // Кнопка «Назад / Артқа»
        if (text.equals("⬅️ Назад / Артқа")) {
            handleBack(chatId, userId);
            return;
        }

        Report report = pendingReports.get(userId);
        if (report == null) {
            send(chatId, t(userId, "unknown_cmd"));
            return;
        }

        switch (state) {
            case "AWAIT_ORG_NAME" -> {
                report.setOrgName(text);
                pushState(userId, "AWAIT_DESCRIPTION");
                send(chatId, t(userId, "report_desc"), true, buildNavKeyboard(userId));
            }
            case "AWAIT_DESCRIPTION" -> {
                report.setDescription(text);
                pushState(userId, "AWAIT_CONTACT");
                send(chatId, t(userId, "report_contact"), true, buildNavKeyboard(userId));
            }
            case "AWAIT_CONTACT" -> {
                report.setContactInfo(text.equals("-") ? null : text);
                submitReport(chatId, userId, report);
            }
            default -> send(chatId, t(userId, "unknown_cmd"));
        }
    }

    private void handleCallback(Update update) {
        long   chatId   = update.getCallbackQuery().getMessage().getChatId();
        long   userId   = update.getCallbackQuery().getFrom().getId();
        String data     = update.getCallbackQuery().getData();
        String username = update.getCallbackQuery().getFrom().getUserName();

        // Выбор языка
        if (data.startsWith("LANG_")) {
            String lang = data.replace("LANG_", "");
            userLang.put(userId, lang);
            send(chatId, t(userId, "lang_changed"));
            sendWelcome(chatId, userId);
            return;
        }

        // Выбор типа репорта
        if (data.startsWith("TYPE_")) {
            Report report = pendingReports.computeIfAbsent(userId, id -> new Report(userId, username));
            switch (data) {
                case "TYPE_FRAUD" -> report.setType(Report.Type.FRAUD);
                case "TYPE_ORG"   -> report.setType(Report.Type.SUSPICIOUS_ORG);
                case "TYPE_OTHER" -> report.setType(Report.Type.OTHER);
            }
            pushState(userId, "AWAIT_ORG_NAME");
            send(chatId, t(userId, "report_name"), true, buildNavKeyboard(userId));
        }
    }

    private void submitReport(long chatId, long userId, Report report) {
        send(chatId, t(userId, "report_done"), true, buildMainKeyboard(userId));
        clearReportState(userId);

        // 1. Уведомить администратора
        SendMessage adminMsg = new SendMessage();
        adminMsg.setChatId(adminChatId);
        adminMsg.setText(report.toAdminMessage());
        adminMsg.setParseMode("Markdown");
        try { execute(adminMsg); } catch (TelegramApiException e) { e.printStackTrace(); }

        // 2. Отправить в API
        new Thread(() -> {
            try {
                String name    = report.getOrgName()     != null ? report.getOrgName()     : "Telegram: @" + report.getUsername();
                String contact = report.getContactInfo() != null ? report.getContactInfo() : "Не указан";
                String type    = report.getType()        != null ? report.getType().getLabel() : "OTHER";
                String desc    = "[" + type + "] " + (report.getDescription() != null ? report.getDescription() : "");

                String json = "{\"name\":\""    + jsonEscapeValue(name)    + "\","
                            + "\"email\":\""   + jsonEscapeValue(contact) + "\","
                            + "\"message\":\"" + jsonEscapeValue(desc)    + "\"}";

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(API_BASE + "/api/contact-reports"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.println("Report API: " + resp.statusCode() + " | " + resp.body());
            } catch (Exception e) {
                System.err.println("Report API error: " + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    // BACK LOGIC (пошаговый откат)
    // ─────────────────────────────────────────────

    /**
     * Откатывает пользователя на один шаг назад.
     * История состояний хранится в stateHistory (Deque).
     *
     * Порядок шагов репорта:
     *   AWAIT_TYPE → AWAIT_ORG_NAME → AWAIT_DESCRIPTION → AWAIT_CONTACT
     *
     * «Назад» из AWAIT_ORG_NAME → возвращаем на AWAIT_TYPE (показываем inline)
     * «Назад» из AWAIT_DESCRIPTION → возвращаем на AWAIT_ORG_NAME
     * «Назад» из AWAIT_CONTACT → возвращаем на AWAIT_DESCRIPTION
     */
    private void handleBack(long chatId, long userId) {
        Deque<String> history = stateHistory.getOrDefault(userId, new ArrayDeque<>());

        // Удаляем текущее состояние
        if (!history.isEmpty()) history.pop();

        String prevState = history.isEmpty() ? "IDLE" : history.peek();

        Report report = pendingReports.get(userId);

        switch (prevState) {
            case "AWAIT_TYPE" -> {
                userState.put(userId, "AWAIT_TYPE");
                // Откатываем выбранный тип
                if (report != null) report.setType(null);

                SendMessage msg = new SendMessage();
                msg.setChatId(chatId);
                msg.setText(t(userId, "back_to_type"));
                msg.setParseMode("Markdown");
                msg.setReplyMarkup(buildTypeKeyboard(userId));
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
            }
            case "AWAIT_ORG_NAME" -> {
                userState.put(userId, "AWAIT_ORG_NAME");
                // Очищаем название организации
                if (report != null) report.setOrgName(null);
                send(chatId, t(userId, "back_to_name"), true, buildNavKeyboard(userId));
            }
            case "AWAIT_DESCRIPTION" -> {
                userState.put(userId, "AWAIT_DESCRIPTION");
                // Очищаем описание
                if (report != null) report.setDescription(null);
                send(chatId, t(userId, "back_to_desc"), true, buildNavKeyboard(userId));
            }
            default -> {
                // Дошли до начала — полный сброс
                cancelAll(chatId, userId);
            }
        }
    }

    /** Добавляет состояние в историю и обновляет текущее */
    private void pushState(long userId, String newState) {
        stateHistory.computeIfAbsent(userId, k -> new ArrayDeque<>()).push(newState);
        userState.put(userId, newState);
    }

    // ─────────────────────────────────────────────
    // CANCEL (полный сброс)
    // ─────────────────────────────────────────────
    private void cancelAll(long chatId, long userId) {
        clearReportState(userId);
        send(chatId, t(userId, "cancelled"), false, buildMainKeyboard(userId));
    }

    /** Очищает все состояния репорта (без удаления языка!) */
    private void clearReportState(long userId) {
        userState.remove(userId);
        pendingReports.remove(userId);
        awaitingScan.remove(userId);
        stateHistory.remove(userId);
    }

    // ─────────────────────────────────────────────
    // MESSAGES (перегруженные методы)
    // ─────────────────────────────────────────────
    private void sendMainMenu(long chatId, long userId) {
        send(chatId, "—", false, buildMainKeyboard(userId));
    }

    private void send(long chatId, String text) {
        send(chatId, text, false, null);
    }

    private void send(long chatId, String text, boolean markdown) {
        send(chatId, text, markdown, null);
    }

    private void send(long chatId, String text, boolean markdown, org.telegram.telegrambots.meta.api.interfaces.Validable keyboard) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.disableWebPagePreview();
        if (markdown) msg.setParseMode("Markdown");
        if (keyboard != null) msg.setReplyMarkup(keyboard);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ─────────────────────────────────────────────
    // KEYBOARDS
    // ─────────────────────────────────────────────
    private InlineKeyboardMarkup buildTypeKeyboard(long userId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(InlineKeyboardButton.builder()
                        .text(t(userId, "type_fraud")).callbackData("TYPE_FRAUD").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder()
                        .text(t(userId, "type_org")).callbackData("TYPE_ORG").build()))
                .keyboardRow(List.of(InlineKeyboardButton.builder()
                        .text(t(userId, "type_other")).callbackData("TYPE_OTHER").build()))
                .build();
    }

    // ─────────────────────────────────────────────
    // MINI JSON PARSER
    // ─────────────────────────────────────────────
    private int parseJsonInt(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx);
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Integer.parseInt(json.substring(start, end).trim()); } catch (Exception e) { return 0; }
    }

    private boolean parseJsonBool(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return true;
        int colon = json.indexOf(':', idx);
        String rest = json.substring(colon + 1).trim();
        return rest.startsWith("true");
    }

    private String parseJsonString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + pattern.length());
        if (start < 0) return null;
        start++;
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        return json.substring(start, end).replace("\\n", "\n").replace("\\\"", "\"");
    }

    private String parseJsonStringArray(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int arrStart = json.indexOf('[', idx);
        int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return "";
        String inner = json.substring(arrStart + 1, arrEnd).trim();
        if (inner.isBlank()) return "";
        return inner.replace("\"", "").replace(",", ", ");
    }

    private String jsonEscape(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                          .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String jsonEscapeValue(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                   .replace("\n", "\\n").replace("\r", "\\r");
    }
}
