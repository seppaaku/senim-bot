package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.util.*;

public class SenimWikiBot extends TelegramLongPollingBot {

    private final String botToken;
    private final String adminChatId;

    private static final String API_BASE = "https://senim-backend-production.up.railway.app";

    // Состояния
    private final Map<Long, String>  userState      = new HashMap<>();
    private final Map<Long, Report>  pendingReports = new HashMap<>();
    private final Set<Long>          awaitingScan   = new HashSet<>();

    // Язык пользователя: "kz" или "ru"
    private final Map<Long, String>  userLang       = new HashMap<>();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ─────────────────────────────────────────────────────────────────
    // TRANSLATIONS
    // ─────────────────────────────────────────────────────────────────
    private String t(long userId, String key) {
        String lang = userLang.getOrDefault(userId, "kz");
        return TRANSLATIONS.getOrDefault(lang + "." + key, key);
    }

    private static final Map<String, String> TRANSLATIONS = new HashMap<>() {{

        // ── WELCOME ──────────────────────────────────────────────────
        put("kz.welcome", """
                🛡️ *SenimWiki ботына қош келдіңіз!*

                Мен не істей аламын:
                🔍 /scan — Күдікті мәтінді тексеру
                📸 Фото жібер — Скриншотты тексеру (OCR + AI)
                📋 /report — Алаяқтық туралы шағым беру
                ❓ /help — Анықтама

                🌐 senim-wiki.vercel.app
                """);
        put("ru.welcome", """
                🛡️ *Добро пожаловать в бот SenimWiki!*

                Что я умею:
                🔍 /scan — Проверить подозрительный текст
                📸 Отправь фото — Проверить скриншот (OCR + AI)
                📋 /report — Подать жалобу на мошенника
                ❓ /help — Справка

                🌐 senim-wiki.vercel.app
                """);

        // ── LANGUAGE SELECT ───────────────────────────────────────────
        put("kz.choose_lang", "🌐 Тілді таңдаңыз / Выберите язык:");
        put("ru.choose_lang", "🌐 Тілді таңдаңыз / Выберите язык:");

        // ── HELP ──────────────────────────────────────────────────────
        put("kz.help", """
                ℹ️ *Анықтама*

                /scan — Мәтін жіберіп AI тексеруі
                📸 *Фото жібер* — Скриншот автоматты тексеріледі
                /report — Алаяқтық шағымын толтыру
                /lang — Тілді ауыстыру
                /cancel — Ағымдағы әрекетті тоқтату

                📧 info@senimwiki.kz
                """);
        put("ru.help", """
                ℹ️ *Справка*

                /scan — Отправь текст для проверки AI
                📸 *Отправь фото* — Скриншот проверится автоматически
                /report — Заполнить жалобу на мошенника
                /lang — Сменить язык
                /cancel — Отменить текущее действие

                📧 info@senimwiki.kz
                """);

        // ── SCAN ──────────────────────────────────────────────────────
        put("kz.scan_prompt",    "🔍 *Мәтінді тексеру режимі*\n\nКүдікті хабарлама мәтінін жіберіңіз.\nБолдырмау үшін /cancel.");
        put("ru.scan_prompt",    "🔍 *Режим проверки текста*\n\nОтправьте текст подозрительного сообщения.\nДля отмены — /cancel.");
        put("kz.scan_wait",      "⏳ Талдануда… AI мәтінді тексеруде.");
        put("ru.scan_wait",      "⏳ Анализируем… AI проверяет текст.");
        put("kz.photo_received", "🧠 Скриншот жүктелді. AI өңдеуде… (OCR + талдау)");
        put("ru.photo_received", "🧠 Скриншот получен. AI обрабатывает… (OCR + анализ)");

        // ── SCAN RESULT ───────────────────────────────────────────────
        put("kz.result_title",    "📊 *Тексеру нәтижесі*");
        put("ru.result_title",    "📊 *Результат проверки*");
        put("kz.result_score",    "📈 *Қауіп деңгейі:*");
        put("ru.result_score",    "📈 *Уровень угрозы:*");
        put("kz.result_safe",     "✅ Қауіпсіз болуы мүмкін");
        put("ru.result_safe",     "✅ Скорее всего безопасно");
        put("kz.result_danger",   "🚨 Алаяқтық қаупі өте жоғары!");
        put("ru.result_danger",   "🚨 Высокий риск мошенничества!");
        put("kz.result_hits",     "⚠️ *Табылған белгілер:*");
        put("ru.result_hits",     "⚠️ *Обнаруженные признаки:*");
        put("kz.result_no_hits",  "✔️ Күдікті маркерлер табылмады.");
        put("ru.result_no_hits",  "✔️ Подозрительных маркеров не найдено.");
        put("kz.result_ocr",      "📝 *OCR мәтін:*");
        put("ru.result_ocr",      "📝 *Распознанный текст:*");

        // ── EMERGENCY ─────────────────────────────────────────────────
        put("kz.emergency", """
                🚨 *Шұғыл іс-қимылдар:*
                • Банктік картаны бұғаттаңыз: *1477*
                • Полицияға хабарлаңыз: *102*
                • Арыз беріңіз: [e-Otinish](https://egov.kz/cms/ru/services/e_app)
                """);
        put("ru.emergency", """
                🚨 *Срочные действия:*
                • Заблокируйте банковскую карту: *1477*
                • Сообщите в полицию: *102*
                • Подайте заявление: [e-Otinish](https://egov.kz/cms/ru/services/e_app)
                """);

        // ── REPORT ───────────────────────────────────────────────────
        put("kz.report_type",     "📋 Шағым *түрін* таңдаңыз:");
        put("ru.report_type",     "📋 Выберите *тип* жалобы:");
        put("kz.report_name",     "📝 Ұйым немесе адамның *атауын* жазыңыз:");
        put("ru.report_name",     "📝 Напишите *название* организации или имя человека:");
        put("kz.report_desc",     "✍️ *Сипаттама* жазыңыз:\n\nБолған оқиғаны егжей-тегжейлі баяндаңыз.");
        put("ru.report_desc",     "✍️ Напишите *описание*:\n\nПодробно опишите что произошло.");
        put("kz.report_contact",  "📞 Байланыс ақпаратыңызды енгізіңіз немесе өткізіп жіберу үшін `-` жіберіңіз.");
        put("ru.report_contact",  "📞 Введите контактные данные (телефон, email) или отправьте `-` чтобы пропустить.");
        put("kz.report_done",     "✅ *Шағым қабылданды!*\n\nРахмет! SenimWiki модераторлары тексереді.\n\n🌐 senim-wiki.vercel.app");
        put("ru.report_done",     "✅ *Жалоба принята!*\n\nСпасибо! Модераторы SenimWiki проверят информацию.\n\n🌐 senim-wiki.vercel.app");

        // ── REPORT TYPES BUTTONS ──────────────────────────────────────
        put("kz.type_fraud",   "🚨 Алаяқтық");
        put("ru.type_fraud",   "🚨 Мошенничество");
        put("kz.type_org",     "🏢 Күдікті ұйым");
        put("ru.type_org",     "🏢 Подозрительная организация");
        put("kz.type_other",   "❓ Басқа");
        put("ru.type_other",   "❓ Другое");

        // ── CANCEL / ERRORS ───────────────────────────────────────────
        put("kz.cancelled",    "❌ Болдырылмады. /scan немесе /report жіберіңіз.");
        put("ru.cancelled",    "❌ Отменено. Отправьте /scan или /report.");
        put("kz.unknown_cmd",  "Белгісіз команда. /help жіберіңіз.");
        put("ru.unknown_cmd",  "Неизвестная команда. Отправьте /help.");
        put("kz.server_error", "❌ Сервер қатесі: ");
        put("ru.server_error", "❌ Ошибка сервера: ");
        put("kz.parse_error",  "⚠️ Нәтижені оқу қатесі.");
        put("ru.parse_error",  "⚠️ Ошибка чтения результата.");
        put("kz.lang_changed", "✅ Тіл қазақшаға ауысты.");
        put("ru.lang_changed", "✅ Язык изменён на русский.");
    }};

    // ─────────────────────────────────────────────────────────────────
    // CONSTRUCTORS & BOT INFO
    // ─────────────────────────────────────────────────────────────────
    public SenimWikiBot(String botToken, String adminChatId) {
        this.botToken    = botToken;
        this.adminChatId = adminChatId;
    }

    @Override public String getBotUsername() { return "senimwiki_bot"; }
    @Override public String getBotToken()    { return botToken; }

    // ─────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────────────────────────
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message msg   = update.getMessage();
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

    // ─────────────────────────────────────────────────────────────────
    // TEXT ROUTING
    // ─────────────────────────────────────────────────────────────────
    private void handleTextMessage(Update update) {
        long   chatId   = update.getMessage().getChatId();
        long   userId   = update.getMessage().getFrom().getId();
        String text     = update.getMessage().getText();
        String username = update.getMessage().getFrom().getUserName();
        String state    = userState.getOrDefault(userId, "IDLE");

        // Первый вход — выбор языка
        if (!userLang.containsKey(userId) && !text.equals("/start")) {
            sendLangSelect(chatId, userId);
            return;
        }

        // Ждём текст для скана
        if (awaitingScan.contains(userId)) {
            awaitingScan.remove(userId);
            scanText(chatId, userId, text);
            return;
        }

        switch (text) {
            case "/start"  -> {
                if (!userLang.containsKey(userId)) sendLangSelect(chatId, userId);
                else sendWelcome(chatId, userId);
            }
            case "/scan"   -> startScanMode(chatId, userId);
            case "/report" -> startReport(chatId, userId, username);
            case "/lang"   -> sendLangSelect(chatId, userId);
            case "/cancel" -> cancelAll(chatId, userId);
            case "/help"   -> send(chatId, t(userId, "help"), true);
            default        -> handleStateInput(chatId, userId, text, state);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LANGUAGE SELECT
    // ─────────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────
    // SCAN: TEXT
    // ─────────────────────────────────────────────────────────────────
    private void startScanMode(long chatId, long userId) {
        awaitingScan.add(userId);
        send(chatId, t(userId, "scan_prompt"), true);
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
            } catch (Exception e) {
                send(chatId, t(userId, "server_error") + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────
    // SCAN: IMAGE (OCR)
    // ─────────────────────────────────────────────────────────────────
    private void handlePhotoScan(long chatId, long userId, Message msg) {
        if (!userLang.containsKey(userId)) {
            sendLangSelect(chatId, userId);
            return;
        }
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

            } catch (Exception e) {
                send(chatId, t(userId, "server_error") + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────
    // FORMAT & SEND SCAN RESULT
    // ─────────────────────────────────────────────────────────────────
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
            sb.append("🏷️ ").append(verdict).append("\n");

            if (hits != null && !hits.isBlank())
                sb.append(t(userId, "result_hits")).append(" ").append(hits).append("\n");
            else
                sb.append(t(userId, "result_no_hits")).append("\n");

            // Модели
            sb.append("\n");
            if (isImage) sb.append("👁️ _Llama 4 Scout OCR_ + ");
            sb.append("🧠 _Llama 3.3 70b_");

            // Экстренные действия
            if (score >= 75)
                sb.append("\n\n").append(t(userId, "emergency"));

            // OCR текст
            if (extracted != null && !extracted.isBlank()) {
                String preview = extracted.length() > 300 ? extracted.substring(0, 300) + "…" : extracted;
                sb.append("\n\n").append(t(userId, "result_ocr")).append("\n`").append(preview).append("`");
            }

            send(chatId, sb.toString(), true);

        } catch (Exception e) {
            send(chatId, t(userId, "parse_error") + "\n`" + jsonBody.substring(0, Math.min(200, jsonBody.length())) + "`", true);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // REPORT FLOW
    // ─────────────────────────────────────────────────────────────────
    private void handleStateInput(long chatId, long userId, String text, String state) {
        Report report = pendingReports.get(userId);
        if (report == null) {
            send(chatId, t(userId, "unknown_cmd"));
            return;
        }
        switch (state) {
            case "AWAIT_ORG_NAME" -> {
                report.setOrgName(text);
                userState.put(userId, "AWAIT_DESCRIPTION");
                send(chatId, t(userId, "report_desc"), true);
            }
            case "AWAIT_DESCRIPTION" -> {
                report.setDescription(text);
                userState.put(userId, "AWAIT_CONTACT");
                send(chatId, t(userId, "report_contact"), true);
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

        // Тип отчёта
        if (data.startsWith("TYPE_")) {
            Report report = pendingReports.computeIfAbsent(userId, id -> new Report(userId, username));
            switch (data) {
                case "TYPE_FRAUD" -> report.setType(Report.Type.FRAUD);
                case "TYPE_ORG"   -> report.setType(Report.Type.SUSPICIOUS_ORG);
                case "TYPE_OTHER" -> report.setType(Report.Type.OTHER);
            }
            userState.put(userId, "AWAIT_ORG_NAME");
            send(chatId, t(userId, "report_name"), true);
        }
    }

    private void startReport(long chatId, long userId, String username) {
        pendingReports.put(userId, new Report(userId, username));
        userState.put(userId, "AWAIT_TYPE");

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(t(userId, "report_type"));
        msg.setParseMode("Markdown");
        msg.setReplyMarkup(buildTypeKeyboard(userId));
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void submitReport(long chatId, long userId, Report report) {
        send(chatId, t(userId, "report_done"), true);

        SendMessage adminMsg = new SendMessage();
        adminMsg.setChatId(adminChatId);
        adminMsg.setText(report.toAdminMessage());
        adminMsg.setParseMode("Markdown");
        try { execute(adminMsg); } catch (TelegramApiException e) { e.printStackTrace(); }

        userState.remove(userId);
        pendingReports.remove(userId);
    }

    private void cancelAll(long chatId, long userId) {
        userState.remove(userId);
        pendingReports.remove(userId);
        awaitingScan.remove(userId);
        send(chatId, t(userId, "cancelled"));
    }

    // ─────────────────────────────────────────────────────────────────
    // MESSAGES & KEYBOARDS
    // ─────────────────────────────────────────────────────────────────
    private void sendWelcome(long chatId, long userId) {
        send(chatId, t(userId, "welcome"), true);
    }

    private void send(long chatId, String text) { send(chatId, text, false); }

    private void send(long chatId, String text, boolean markdown) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId);
        msg.setText(text);
        msg.disableWebPagePreview();
        if (markdown) msg.setParseMode("Markdown");
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

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

    // ─────────────────────────────────────────────────────────────────
    // MINI JSON PARSER (без зависимостей)
    // ─────────────────────────────────────────────────────────────────
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
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "\"";
    }
}