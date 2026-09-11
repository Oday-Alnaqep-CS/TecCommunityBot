package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Main extends TelegramLongPollingBot {



    private final long[] ADMIN_USER_ID = {6390429747L, 8529164463L};
    private static final String DB_URL = "jdbc:sqlite:bot_database.db";
    private String adminState = "IDLE";
    private String broadcastTarget = "ALL";

    @Override
    public String getBotUsername() {
        return "e_x_h_8231BOT";
    }

    @Override
    public String getBotToken() {
        return "8058159638:AAGapeq5L1f1hyNX2RiBeDlCUM0-i-R9920";
    }

    public Main() {
        initDatabaseTables();
    }

    private Connection connect() {
        try {
            return DriverManager.getConnection(DB_URL);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void initDatabaseTables() {
        String[] queries = {
                "CREATE TABLE IF NOT EXISTS BotChats (ChatId BIGINT PRIMARY KEY, ChatName TEXT, IsGroup INTEGER);",
                "CREATE TABLE IF NOT EXISTS BadWords (Word TEXT PRIMARY KEY);",
                "CREATE TABLE IF NOT EXISTS CustomCommands (Id INTEGER PRIMARY KEY AUTOINCREMENT, CommandText TEXT);",
                "CREATE TABLE IF NOT EXISTS RulesList (Id INTEGER PRIMARY KEY AUTOINCREMENT, RuleText TEXT);",
                "CREATE TABLE IF NOT EXISTS BannedMutedUsers (UserId BIGINT PRIMARY KEY, UserName TEXT, IsBanned INTEGER DEFAULT 0, IsMuted INTEGER DEFAULT 0, WarningCount INTEGER DEFAULT 0);",
                "CREATE TABLE IF NOT EXISTS Admins (UserId BIGINT PRIMARY KEY, AdminName TEXT);"
        };

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            for (String query : queries) {
                stmt.execute(query);
            }

            try {
                stmt.executeUpdate("ALTER TABLE BannedMutedUsers ADD COLUMN WarningCount INTEGER DEFAULT 0;");
            } catch (Exception ignored) {}

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM BadWords");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO BadWords VALUES ('سب'), ('شتم'), ('نصب')");
            }
            rs.close();

            rs = stmt.executeQuery("SELECT COUNT(*) FROM CustomCommands");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO CustomCommands (CommandText) VALUES ('/support - للتواصل مع الدعم'), ('/channels - قنواتنا الرسمية')");
            }
            rs.close();

            rs = stmt.executeQuery("SELECT COUNT(*) FROM RulesList");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO RulesList (RuleText) VALUES ('1️⃣ الاحترام المتبادل وعدم الإساءة لأي عضو.'), ('2️⃣ يمنع منعاً باتاً نشر الروابط الخارجية والسبام.'), ('3️⃣ طرح الأسئلة البرمجية في الأقسام المخصصة لها.')");
            }
            rs.close();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void trackChat(Chat chat) {
        String sql = "INSERT INTO BotChats(ChatId, ChatName, IsGroup) VALUES(?, ?, ?) " +
                "ON CONFLICT(ChatId) DO UPDATE SET ChatName=excluded.ChatName;";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, chat.getId());
            pstmt.setString(2, chat.isUserChat() ? chat.getFirstName() : chat.getTitle());
            pstmt.setInt(3, chat.isUserChat() ? 0 : 1);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void muteOrRestrictUser(long chatId, long userId) {
        RestrictChatMember restrict = new RestrictChatMember();
        restrict.setChatId(String.valueOf(chatId));
        restrict.setUserId(userId);

        // استخدام المنشئ العادي لكائن الصلاحيات
        ChatPermissions permissions = new ChatPermissions();
        permissions.setCanSendMessages(false);
        permissions.setCanSendAudios(false);
        permissions.setCanSendDocuments(false);
        permissions.setCanSendPhotos(false);
        permissions.setCanSendVideos(false);
        permissions.setCanSendVideoNotes(false);
        permissions.setCanSendVoiceNotes(false);
        permissions.setCanSendPolls(false);
        permissions.setCanSendOtherMessages(false);
        permissions.setCanAddWebPagePreviews(false);

        restrict.setPermissions(permissions);

        try {
            execute(restrict);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private List<Long> getActiveChats(String targetType) {
        List<Long> chats = new ArrayList<>();
        String query = "SELECT ChatId, IsGroup FROM BotChats";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                long chatId = rs.getLong("ChatId");
                boolean isGroup = rs.getInt("IsGroup") == 1;
                if (targetType.equals("PRIVATE") && isGroup) continue;
                if (targetType.equals("GROUPS") && !isGroup) continue;
                chats.add(chatId);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return chats;
    }

    private List<String> getBadWords() {
        List<String> list = new ArrayList<>();
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT Word FROM BadWords")) {
            while (rs.next()) list.add(rs.getString("Word"));
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private List<String> getCustomCommands() {
        List<String> list = new ArrayList<>();
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT CommandText FROM CustomCommands")) {
            while (rs.next()) list.add(rs.getString("CommandText"));
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private List<String> getRules() {
        List<String> list = new ArrayList<>();
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT RuleText FROM RulesList")) {
            while (rs.next()) list.add(rs.getString("RuleText"));
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private void deleteMessageSafe(long chatId, int messageId) {
        try {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(String.valueOf(chatId));
            deleteMessage.setMessageId(messageId);
            execute(deleteMessage);
        } catch (Exception e) {}
    }

    private void sendHtmlText(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    public boolean addBadWord(String word) {
        // استخدام INSERT OR IGNORE لتفادي أخطاء التكرار إذا كانت الكلمة مخزنة مسبقاً
        String query = "INSERT OR IGNORE INTO BadWords (Word) VALUES (?)";

        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setString(1, word);
            // ترجع true إذا تمت الإضافة بنجاح، و false إذا كانت موجودة مسبقاً
            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isAdminUser(long userId) {
        // 1. فحص الآيدي الموجودة في مصفوفة الأدمنز الأساسيين
        for (long id : ADMIN_USER_ID) {
            if (userId == id) return true;
        }

        // 2. فحص بقية المشرفين من جدول قاعدة البيانات (Admins)
        String query = "SELECT COUNT(*) FROM Admins WHERE UserId = ?";
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setLong(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                CallbackQuery callbackQuery = update.getCallbackQuery();
                long chatId = callbackQuery.getMessage().getChatId();
                long userId = callbackQuery.getFrom().getId();
                String userName = callbackQuery.getFrom().getFirstName();
                String data = callbackQuery.getData();
                int messageId = callbackQuery.getMessage().getMessageId();

                if (callbackQuery.getMessage() instanceof org.telegram.telegrambots.meta.api.objects.Message) {
                    org.telegram.telegrambots.meta.api.objects.Message msg = (org.telegram.telegrambots.meta.api.objects.Message) callbackQuery.getMessage();
                    trackChat(msg.getChat());
                }

                switch (data) {
                    case "btn_rules":
                        List<String> rulesList = getRules();
                        StringBuilder sbRules = new StringBuilder("📜 <b>قوانين المجتمع:</b>\n\n");
                        if(rulesList.isEmpty()) sbRules.append("لا توجد قوانين مضافة حالياً.");
                        for (int i = 0; i < rulesList.size(); i++) {
                            sbRules.append((i + 1)).append(". ").append(rulesList.get(i)).append("\n");
                        }
                        sendHtmlText(chatId, sbRules.toString());
                        break;
                    case "btn_profile":
                        sendHtmlText(chatId, "👤 <b>ملفك الشخصي:</b>\n\n• الاسم: <b>" + userName + "</b>\n• المعرف: <code>" + userId + "</code>\n• الرتبة: عضو نشط 🚀");
                        break;
                    case "btn_resources":
                        sendHtmlText(chatId, "📚 <b>المصادر التعليمية:</b>\nقريباً أقسام مخصصة لتعلم البرمجة وهندسة البرمجيات.");
                        break;
                    case "btn_projects":
                        sendHtmlText(chatId, "💻 <b>المشاريع البرمجية:</b>\nقريباً إطلاق معرض المشاريع المفتوحة لمجتمع الدعم البرمجي!");
                        break;
                    case "btn_custom_commands":
                        List<String> customCommandsList = getCustomCommands();
                        StringBuilder customCmds = new StringBuilder("💻 <b>قائمة الأوامر والمواضيع المخصصة:</b>\n\n");
                        if (customCommandsList.isEmpty()) {
                            customCmds.append("لا توجد أوامر مخصصة متاحة حالياً.");
                        } else {
                            for (int i = 0; i < customCommandsList.size(); i++) {
                                customCmds.append("• ").append(customCommandsList.get(i)).append("\n");
                            }
                        }
                        sendHtmlText(chatId, customCmds.toString());
                        break;

                    case "admin_panel":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) {
                            adminState = "IDLE";
                            sendAdminPanel(chatId);
                        } else {
                            sendHtmlText(chatId, "⚠️ عذراً، هذه اللوحة مخصصة للأدمن فقط!");
                        }
                        break;

                    case "admin_stats_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendStatsMenu(chatId);
                        break;
                    case "stats_overview":
                        if (isAdminUser(userId)) {
                            sendHtmlText(chatId, "📊 <b>الملخص الإحصائي العام:</b>\n\n• الشاتات المسجلة بقاعدة البيانات: <b>" + getActiveChats("ALL").size() + "</b>\n• الكلمات المحظورة: <b>" + getBadWords().size() + "</b>");
                        }
                        break;
                    case "stats_words_list":
                        if (isAdminUser(userId)) {
                            sendHtmlText(chatId, "🛡️ <b>الكلمات المحظورة:</b>\n• " + String.join("\n• ", getBadWords()));
                        }
                        break;

                    case "admin_settings_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendSettingsMenu(chatId);
                        break;

                    case "set_words_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendWordsSubMenu(chatId);
                        break;
                    case "word_add_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "ADD_WORD";
                            sendHtmlText(chatId, "➕ أرسل الكلمة المحظورة الجديدة لحفظها.");
                        }
                        break;

                    case "word_bulk_add_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "ADD_WORD";
                            sendHtmlText(chatId, "📥 <b>إضافة قائمة كلمات محظورة</b>\n\nأرسل الآن القائمة (سواء بمسافات أو تحت بعضها في أسطر) وسأقوم بحفظها دفعة واحدة.");
                        }
                        break;

                    case "word_remove_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "REMOVE_WORD";
                            sendHtmlText(chatId, "🗑️ أرسل الكلمة المراد حذفها.");
                        }
                        break;

                    case "set_commands_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendCommandsSubMenu(chatId);
                        break;
                    case "cmd_add_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "ADD_CMD";
                            sendHtmlText(chatId, "➕ أرسل نص الأمر الجديد لحفظه.");
                        }
                        break;
                    case "cmd_remove_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "REMOVE_CMD";
                            sendHtmlText(chatId, "🗑️ أرسل رقم أو نص الأمر المراد حذفه.");
                        }
                        break;
                    case "stats_commands_list":
                        if (isAdminUser(userId)) {
                            sendHtmlText(chatId, "🤖 <b>الأوامر المخصصة:</b>\n• " + String.join("\n• ", getCustomCommands()));
                        }
                        break;

                    case "set_rules_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendRulesSubMenu(chatId);
                        break;
                    case "rule_add_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "ADD_RULE";
                            sendHtmlText(chatId, "➕ أرسل نص القانون الجديد لحفظه.");
                        }
                        break;
                    case "rule_remove_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "REMOVE_RULE";
                            sendHtmlText(chatId, "🗑️ أرسل نص القانون المراد حذفه.");
                        }
                        break;

                    case "admin_banned_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendBannedUsersManagement(chatId);
                        break;

                    case "admin_admins_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) {
                            sendAdminsMenu(chatId);
                        }
                        break;

                    case "ban_user_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "BAN_USER";
                            sendHtmlText(chatId, "⛔ أرسل (معرف المستخدم - User ID) المراد حظره:");
                        }
                        break;
                    case "unban_user_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "UNBAN_USER";
                            sendHtmlText(chatId, "✅ أرسل (معرف المستخدم - User ID) لرفع الحظر عنه:");
                        }
                        break;
                    case "mute_user_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "MUTE_USER";
                            sendHtmlText(chatId, "🔇 أرسل (معرف المستخدم - User ID) لكتمه:");
                        }
                        break;
                    case "unmute_user_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "UNMUTE_USER";
                            sendHtmlText(chatId, "🔊 أرسل (معرف المستخدم - User ID) لرفع الكتم عنه:");
                        }
                        break;
                    case "kick_user_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "KICK_USER";
                            sendHtmlText(chatId, "🚷 أرسل (معرف المستخدم - User ID) لطرده من الجروب:");
                        }
                        break;

                    case "list_banned_muted_users":
                        if (isAdminUser(userId)) {
                            StringBuilder statusReport = new StringBuilder("📋 <b>قائمة الأعضاء المقيدين والمخالفين:</b>\n\n");
                            try (Connection conn = connect();
                                 Statement stmt = conn.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT * FROM BannedMutedUsers WHERE IsBanned = 1 OR IsMuted = 1 OR WarningCount > 0")) {

                                boolean found = false;
                                while (rs.next()) {
                                    found = true;
                                    long uId = rs.getLong("UserId");
                                    String uName = rs.getString("UserName");
                                    boolean bBanned = rs.getInt("IsBanned") == 1;
                                    boolean bMuted = rs.getInt("IsMuted") == 1;
                                    int warns = rs.getInt("WarningCount");

                                    statusReport.append("• 👤 ").append(uName != null ? uName : "مستخدم").append("\n");
                                    statusReport.append("  🆔 الآيدي: <code>").append(uId).append("</code>\n");
                                    statusReport.append("  ⚠️ عدد الإنذارات: <b>").append(warns).append("/3</b>\n");
                                    statusReport.append("  ⛔ محظور: <b>").append(bBanned ? "نعم 🔴" : "لا 🟢").append("</b>\n");
                                    statusReport.append("  🔇 مكتوم: <b>").append(bMuted ? "نعم 🔴" : "لا 🟢").append("</b>\n\n");
                                }
                                if (!found) {
                                    statusReport.append("✨ لا يوجد أي أعضاء مخالفين أو مقيدين حالياً.");
                                }
                            } catch (SQLException e) {
                                e.printStackTrace();
                                statusReport.append("⚠️ حدث خطأ أثناء جلب البيانات.");
                            }
                            sendHtmlText(chatId, statusReport.toString());
                        }
                        break;

                    case "admin_add_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "ADD_ADMIN";
                            sendHtmlText(chatId, "➕ <b>إضافة مشرف جديد</b>\n\nأرسل الآن (معرف المستخدم - User ID) الخاص بالشخص المراد ترقيته كأدمن:");
                        }
                        break;

                    case "admin_remove_prompt":
                        if (isAdminUser(userId)) {
                            adminState = "REMOVE_ADMIN";
                            sendHtmlText(chatId, "🗑️ <b>إزالة مشرف</b>\n\nأرسل الآن (معرف المستخدم - User ID) الخاص بالمشرف المراد إزالته:");
                        }
                        break;

                    case "list_admins":
                        if (isAdminUser(userId)) {
                            StringBuilder sbAdmins = new StringBuilder("📋 <b>قائمة المشرفين المساعدين:</b>\n\n");
                            try (Connection conn = connect();
                                 Statement stmt = conn.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT * FROM Admins")) {
                                boolean found = false;
                                while (rs.next()) {
                                    found = true;
                                    sbAdmins.append("• الآيدي: <code>").append(rs.getLong("UserId")).append("</code> | الاسم: <b>").append(rs.getString("AdminName")).append("</b>\n");
                                }
                                if (!found) sbAdmins.append("لا توجد مشرفون مضافون حالياً.");
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                            sendHtmlText(chatId, sbAdmins.toString());
                        }
                        break;

                    case "admin_broadcast_menu":
                        deleteMessageSafe(chatId, messageId);
                        if (isAdminUser(userId)) sendBroadcastMenu(chatId);
                        break;
                    case "bc_all":
                    case "bc_private":
                    case "bc_groups":
                        if (isAdminUser(userId)) {
                            adminState = "BROADCAST";
                            if (data.equals("bc_all")) broadcastTarget = "ALL";
                            else if (data.equals("bc_private")) broadcastTarget = "PRIVATE";
                            else if (data.equals("bc_groups")) broadcastTarget = "GROUPS";
                            sendHtmlText(chatId, "📢 تم تحديد الوجهة (" + broadcastTarget + "). أرسل الإعلان (نص أو صورة) الآن.");
                        }
                        break;
                }
                return;
            }

            if (update.hasMessage()) {
                Message message = update.getMessage();
                long chatId = message.getChatId();
                long userId = message.getFrom().getId();
                boolean isPrivateChat = message.getChat().isUserChat();
                boolean isAdmin = (isAdminUser(userId));

                trackChat(message.getChat());

                if (message.getNewChatMembers() != null && !message.getNewChatMembers().isEmpty()) {
                    for (User newUser : message.getNewChatMembers()) {
                        if (newUser.getUserName() != null && newUser.getUserName().equals(getBotUsername())) {
                            continue;
                        }
                        sendWelcomeMessage(chatId, newUser); // تمرير الكائن بالكامل هنا لتتوافق مع الدالة
                    }
                    return;
                }

                String text = message.hasText() ? message.getText().trim() : "";



                if (text.equals("/help") || text.equals("المساعدة العامة 📚")) {
                    String helpText = "🛡️ <b>مركز الدعم الفني والخدمات</b>\n" +
                            "                                          ━━━━━━━━━━━━━━━━━━━\n\n" +
                            "✨ أهلاً بك عزيزي المستخدم في مساحة الدعم الخاصة بنا.\n" +
                            "إذا واجهتك أي مشكلة برمجية أو استفسار، يمكنك التواصل مباشرة مع المطور المسؤول:\n\n" +
                            "💠 <b>المطور الرئيسي:</b> عدي النقيب\n" +
                            "🔗 <b>المعرف:</b> @Dev_Oday_AlNaqep\n\n" +
                            "💡 <i>نحن هنا لمساعدتك دائماً وتطوير أفكارك إلى واقع!</i>";

                    SendMessage helpMsg = new SendMessage();
                    helpMsg.setChatId(String.valueOf(chatId));
                    helpMsg.setText(helpText);
                    helpMsg.setParseMode("HTML");

                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();

                    List<InlineKeyboardButton> row1 = new ArrayList<>();
                    InlineKeyboardButton btnContact = new InlineKeyboardButton("💬 مراسلة المطور مباشرة");
                    btnContact.setUrl("https://t.me/Dev_Oday_AlNaqep");
                    row1.add(btnContact);

                    List<InlineKeyboardButton> row2 = new ArrayList<>();
                    InlineKeyboardButton btnChannel = new InlineKeyboardButton("📢 قناة المشروع والمجتمع");
                    btnChannel.setUrl("https://t.me/codeprojectlab");
                    row2.add(btnChannel);

                    rows.add(row1);
                    rows.add(row2);
                    markup.setKeyboard(rows);
                    helpMsg.setReplyMarkup(markup);

                    try {
                        execute(helpMsg);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }


                if (text.equals("/profile") || text.equals("ملفك الشخصي 👤")) {
                    String firstName = message.getFrom().getFirstName() != null ? message.getFrom().getFirstName() : "مستخدم";
                    String lastName = message.getFrom().getLastName() != null ? message.getFrom().getLastName() : "";
                    String username = message.getFrom().getUserName() != null ? "@" + message.getFrom().getUserName() : "بدون معرف";
                    String role = isAdminUser(userId) ? "👑 مشرف رئيسي / مطور" : "⭐ عضو مميز في المجتمع";

                    String profileText = "👤 <b>بطاقة الملف الشخصي الذكية</b>\n" +
                            "                                          ━━━━━━━━━━━━━━━━━━━\n\n" +
                            "🔹 <b>الاسم:</b> " + firstName + " " + lastName + "\n" +
                            "🔹 <b>المعرف:</b> " + username + "\n" +
                            "🔹 <b>الآيدي:</b> <code>" + userId + "</code>\n" +
                            "🔹 <b>الرتبة:</b> " + role + "\n\n" +
                            "📊 <i>شكراً لكونك جزءاً أساسياً من مجتمعنا التقني.</i>";

                    SendMessage profileMsg = new SendMessage();
                    profileMsg.setChatId(String.valueOf(chatId));
                    profileMsg.setText(profileText);
                    profileMsg.setParseMode("HTML");

                    try {
                        execute(profileMsg);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }


                if (text.equals("/rules") || text.equals("القوانين الأساسية 📜")) {
                    List<String> rulesList = getRules();
                    StringBuilder rulesText = new StringBuilder("📜 <b>دستور وقوانين المجتمع</b>\n" +
                            "                                          ━━━━━━━━━━━━━━━━━━━\n\n");

                    int index = 1;
                    for (String rule : rulesList) {
                        rulesText.append("🔹 ").append(rule).append("\n\n");
                        index++;
                    }
                    rulesText.append("⚠️ <i>نرجو الالتزام بالتعليمات لضمان بيئة راقية للجميع.</i>");

                    sendHtmlText(chatId, rulesText.toString());
                    return;
                }


                if (text.equals("/id") || text.equals("/myid")) {
                    String idMessage = "🆔 <b>معرفك الرقمي (User ID):</b>\n" +
                            "⚡ <code>" + userId + "</code>\n\n" +
                            "💡 <i>اضغط على الرقم للنسخ السريع.</i>";
                    sendHtmlText(chatId, idMessage);
                    return;
                }

                if ("ADD_WORD".equals(adminState) && isAdmin) {
                    adminState = "NORMAL"; // إعادة الحالة للوضع الطبيعي فوراً

                    // تقسيم النص (سواء أسطر، مسافات، أو فواصل)
                    String[] wordsArray = text.split("[,\\r?\\n|\\s]+");
                    int addedCount = 0;
                    int duplicateCount = 0;

                    for (String word : wordsArray) {
                        String cleanWord = word.trim().toLowerCase();
                        if (!cleanWord.isBlank()) {
                            // استدعاء دالة الإضافة الموجودة في ملف DatabaseManager لديك
                            boolean success = addBadWord(cleanWord);
                            if (success) {
                                addedCount++;
                            } else {
                                duplicateCount++;
                            }
                        }
                    }

                    // إرسال النتيجة للأدمن
                    sendHtmlText(chatId, "✅ <b>تمت إضافة الكلمات بنجاح!</b>\n\n" +
                            "➕ تم إضافة: <b>" + addedCount + "</b> كلمة.\n" +
                            "🔄 كلمات موجودة مسبقاً أو حدث خطأ: <b>" + duplicateCount + "</b>");
                    return; // الخروج لكي لا يتم معالجة القائمة كرسالة عادية
                }

                // معالجة إضافة أدمن جديد عبر إرسال الـ User ID
                if ("ADD_ADMIN".equals(adminState) &&  (isAdminUser(userId))){
                    adminState = "NORMAL";
                    try {
                        long newAdminId = Long.parseLong(text);
                        String adminName = message.getFrom().getFirstName();

                        String query = "INSERT OR REPLACE INTO Admins (UserId, AdminName) VALUES (?, ?)";
                        try (Connection conn = connect();
                             PreparedStatement pstmt = conn.prepareStatement(query)) {
                            pstmt.setLong(1, newAdminId);
                            pstmt.setString(2, "مشرف مساعد");
                            pstmt.executeUpdate();
                        }
                        sendHtmlText(chatId, "✅ <b>تمت ترقية المستخدم بنجاح وأصبح مشرفاً في البوت!</b>\n🆔 الآيدي: <code>" + newAdminId + "</code>");
                    } catch (NumberFormatException e) {
                        sendHtmlText(chatId, "⚠️ الخطأ: يرجى إرسال آيدي صحيح يتكون من أرقام فقط.");
                    } catch (SQLException e) {
                        e.printStackTrace();
                        sendHtmlText(chatId, "⚠️ حدث خطأ أثناء حفظ المشرف في قاعدة البيانات.");
                    }
                    return;
                }

// معالجة حذف أدمن عبر إرسال الـ User ID
                if ("REMOVE_ADMIN".equals(adminState) &&  (isAdminUser(userId))) {
                    adminState = "NORMAL";
                    try {
                        long targetAdminId = Long.parseLong(text);
                        String query = "DELETE FROM Admins WHERE UserId = ?";
                        try (Connection conn = connect();
                             PreparedStatement pstmt = conn.prepareStatement(query)) {
                            pstmt.setLong(1, targetAdminId);
                            int rowsAffected = pstmt.executeUpdate();
                            if (rowsAffected > 0) {
                                sendHtmlText(chatId, "✅ <b>تمت إزالة المشرف من القائمة بنجاح.</b>");
                            } else {
                                sendHtmlText(chatId, "⚠️ هذا المستخدم غير موجود في قائمة المشرفين الأساسيين أو المساعدين.");
                            }
                        }
                    } catch (NumberFormatException e) {
                        sendHtmlText(chatId, "⚠️ الخطأ: يرجى إرسال آيدي صحيح.");
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                    return;
                }


                // نظام الكلمات المحظورة المتدرج (3 إنذارات)
                if (!isPrivateChat && !isAdmin && !text.isEmpty()) {
                    List<String> badWords = getBadWords();
                    String lowerText = text.toLowerCase();
                    for (String word : badWords) {
                        if (lowerText.contains(word.toLowerCase())) {
                            deleteMessageSafe(chatId, message.getMessageId());

                            int currentWarnings = 0;
                            String userName = message.getFrom().getFirstName();
                            String userTag = message.getFrom().getUserName() != null ? "@" + message.getFrom().getUserName() : userName;

                            try (Connection conn = connect()) {
                                String selectSql = "SELECT WarningCount FROM BannedMutedUsers WHERE UserId = ?";
                                try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                                    pstmt.setLong(1, userId);
                                    ResultSet rs = pstmt.executeQuery();
                                    if (rs.next()) {
                                        currentWarnings = rs.getInt("WarningCount");
                                    }
                                }

                                currentWarnings++;

                                String updateSql = "INSERT INTO BannedMutedUsers(UserId, UserName, WarningCount) VALUES(?, ?, ?) " +
                                        "ON CONFLICT(UserId) DO UPDATE SET UserName=excluded.UserName, WarningCount=excluded.WarningCount;";
                                try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                                    pstmt.setLong(1, userId);
                                    pstmt.setString(2, userName);
                                    pstmt.setInt(3, currentWarnings);
                                    pstmt.executeUpdate();
                                }
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }

                            SendMessage warnMsg = new SendMessage();
                            warnMsg.setChatId(String.valueOf(chatId));
                            warnMsg.setParseMode("HTML");

                            try {
                                if (currentWarnings == 1) {
                                    // ⚠️ الإنذار الأول
                                    warnMsg.setText(
                                            "⚠️ <b>تنبيه أول (1/3)</b>\n\n" +
                                                    "👤 العضو: <b>" + userTag + "</b>\n\n" +
                                                    "🚫 تم حذف رسالتك لاحتوائها على كلمات أو عبارات محظورة.\n\n" +
                                                    "📌 نرجو الالتزام بقوانين المجتمع وعدم تكرار المخالفة.\n\n" +
                                                    "⚠️ <b>تنبيه:</b> لديك إنذاران متبقيان قبل اتخاذ إجراءات تقييدية."
                                    );
                                    execute(warnMsg);

                                } else if (currentWarnings == 2) {
                                    // 🔇 الإنذار الثاني + تقييد العضو
                                    muteOrRestrictUser(chatId, userId);

                                    warnMsg.setText(
                                            "🔇 <b>إنذار ثانٍ (2/3)</b>\n\n" +
                                                    "👤 العضو: <b>" + userTag + "</b>\n\n" +
                                                    "🚫 تم رصد تكرار استخدام كلمات أو عبارات محظورة.\n\n" +
                                                    "🔒 تم تقييدك ومنعك من الكتابة في المجموعة.\n\n" +
                                                    "⛔ <b>الإنذار الثالث (3/3) سيؤدي إلى حظرك من المجموعة.</b>\n\n" +
                                                    "📜 يرجى الالتزام بقوانين مجتمع <b>TEC</b>."
                                    );
                                    execute(warnMsg);

                                } else {
                                    // ⛔ الإنذار الثالث + الحظر النهائي
                                    BanChatMember ban = new BanChatMember();
                                    ban.setChatId(String.valueOf(chatId));
                                    ban.setUserId(userId);
                                    execute(ban);

                                    warnMsg.setText(
                                            "🚷 <b>تم الحظر النهائي (3/3)</b>\n\n" +
                                                    "👤 العضو: <b>" + userTag + "</b>\n\n" +
                                                    "🚫 تم تجاوز الحد الأقصى المسموح به من الإنذارات.\n\n" +
                                                    "⛔ تم حظرك من المجموعة بسبب تكرار المخالفات واستخدام كلمات أو عبارات محظورة.\n\n" +
                                                    "📜 نرجو الالتزام بقوانين مجتمع <b>TEC</b>."
                                    );
                                    execute(warnMsg);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            return; // الخروج من الدالة فور معالجة الكلمة المحظورة
                        }
                    }
                }

                if (text.equals("/start") && isPrivateChat) {
                    sendWelcomeMessage(chatId, message.getFrom());
                    return;
                }

                if (text.equals("/admin")) {
                    // 1. التحقق هل المستخدم أدمن أو مشرف مضاف
                    if (!isAdminUser(userId)) {
                        sendHtmlText(chatId, "⚠️ عذراً، هذه اللوحة مخصصة للمشرفين فقط!");
                        return;
                    }

                    // 2. إذا كان مصرحاً له، استدعِ الدالة لترسل له لوحة التحكم بالأزرار الفخمة
                    sendAdminPanel(chatId);
                    return;
                }

                if (isPrivateChat && isAdmin && !adminState.equals("IDLE")) {
                    handleAdminInput(chatId, message);
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendWelcomeMessage(long chatId, org.telegram.telegrambots.meta.api.objects.User user) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));


        String firstName = (user != null) ? user.getFirstName() : "";
        String userName = (user != null) ? user.getUserName() : null;


        String userDisplay;
        if (userName != null && !userName.isBlank()) {
            userDisplay = "@" + userName;
        } else if (firstName != null && !firstName.isBlank()) {
            userDisplay = "<b>" + firstName + "</b>";
        } else {
            userDisplay = "<b>عضونا الجديد</b>";
        }


        String welcomeText =
                "  🚀 أهلاً بك : ( " + userDisplay + " ) \n" +
                        "في مجتمع التطور التقني | <b>TEC</b>\n\n" +

                        "💻 مجتمع يجمع المبرمجين والمهتمين بالتقنية، " +
                        "لتبادل المعرفة ومشاركة الخبرات وبناء المهارات وتطوير المشاريع.\n\n" +

                        "🌟 معًا نرتقي بالمعرفة، " +
                        "وبالإصرار نصنع الإنجاز، " +
                        "وبالعزيمة نحوّل الأفكار إلى واقع، " +
                        "وبالتكاتف نبني مجتمعًا أقوى ومستقبلًا أفضل.\n\n" +

                        "✨ يمكنك استعراض أقسام المجتمع وقوانينه " +
                        "عبر الأزرار أدناه 👇\n\n" +

                        "🔥 <b>Learn • Build • Share • Grow</b>";

        message.setText(welcomeText);
        message.setParseMode("HTML");

        // تصميم الأزرار التفاعلية
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // الصف الأول: زر القوانين والتعليمات
        List<InlineKeyboardButton> row0 = new ArrayList<>();
        InlineKeyboardButton btnRules = new InlineKeyboardButton("📜 القوانين والتعليمات — Rules");
        btnRules.setUrl("https://t.me/TEC_general/1667");
        row0.add(btnRules);

        // الصف الثاني: المشاريع والتعلم والموارد
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btnProjects = new InlineKeyboardButton("🚀 TEC Projects");
        btnProjects.setUrl("https://t.me/TEC_general/1424");

        InlineKeyboardButton btnLearning = new InlineKeyboardButton("📚 TEC Learning");
        btnLearning.setUrl("https://t.me/TEC_general/1423");

        row1.add(btnProjects);
        row1.add(btnLearning);

        // الصف الثالث: الأسئلة وحل المشاكل
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnQandA = new InlineKeyboardButton(" Q & A & Issues ❓");
        btnQandA.setUrl("https://t.me/TEC_general/1632");
        row2.add(btnQandA);

        rows.add(row0);
        rows.add(row1);
        rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleAdminInput(long chatId, Message message) {
        String currentAdminState = adminState;
        adminState = "IDLE";

        try (Connection conn = connect()) {
            switch (currentAdminState) {
                case "BROADCAST":
                    int successCount = 0;
                    List<Long> targets = getActiveChats(broadcastTarget);
                    for (long targetChatId : targets) {
                        try {
                            if (message.hasPhoto() && !message.getPhoto().isEmpty()) {
                                String fileId = message.getPhoto().get(message.getPhoto().size() - 1).getFileId();
                                SendPhoto sendPhoto = new SendPhoto();
                                sendPhoto.setChatId(String.valueOf(targetChatId));
                                sendPhoto.setPhoto(new InputFile(fileId));
                                if (message.getCaption() != null) {
                                    sendPhoto.setCaption("📢 <b>[إعلان إداري عام]</b>\n" + message.getCaption());
                                    sendPhoto.setParseMode("HTML");
                                }
                                execute(sendPhoto);
                            } else if (message.hasText()) {
                                sendHtmlText(targetChatId, "📢 <b>[إعلان إداري عام]</b>\n\n" + message.getText());
                            }
                            successCount++;
                        } catch (Exception e) {}
                    }
                    sendHtmlText(chatId, "✅ <b>تمت الإذاعة بنجاح!</b>\nتم الإرسال إلى <b>" + successCount + "</b> شات من أصل " + targets.size() + ".");
                    break;

                case "ADD_WORD":
                    if (message.hasText()) {
                        String newWord = message.getText().trim().toLowerCase();
                        try (PreparedStatement pst = conn.prepareStatement("INSERT INTO BadWords(Word) VALUES(?)")) {
                            pst.setString(1, newWord);
                            pst.executeUpdate();
                            sendHtmlText(chatId, "✅ تمت إضافة الكلمة (<code>" + newWord + "</code>) بنجاح.");
                        } catch (SQLException e) {
                            sendHtmlText(chatId, "⚠️ الكلمة موجودة مسبقاً.");
                        }
                    }
                    break;

                case "REMOVE_WORD":
                    if (message.hasText()) {
                        String targetWord = message.getText().trim().toLowerCase();
                        try (PreparedStatement pst = conn.prepareStatement("DELETE FROM BadWords WHERE Word = ?")) {
                            pst.setString(1, targetWord);
                            int rows = pst.executeUpdate();
                            if (rows > 0) sendHtmlText(chatId, "🗑️ تمت إزالة الكلمة بنجاح.");
                            else sendHtmlText(chatId, "⚠️ الكلمة غير موجودة.");
                        }
                    }
                    break;

                case "ADD_CMD":
                    if (message.hasText()) {
                        try (PreparedStatement pst = conn.prepareStatement("INSERT INTO CustomCommands(CommandText) VALUES(?)")) {
                            pst.setString(1, message.getText().trim());
                            pst.executeUpdate();
                            sendHtmlText(chatId, "✅ تم حفظ الأمر الجديد.");
                        }
                    }
                    break;

                case "REMOVE_CMD":
                    if (message.hasText()) {
                        try (PreparedStatement pst = conn.prepareStatement("DELETE FROM CustomCommands WHERE CommandText LIKE ?")) {
                            pst.setString(1, "%" + message.getText().trim() + "%");
                            int rows = pst.executeUpdate();
                            if(rows > 0) sendHtmlText(chatId, "🗑️ تم حذف الأمر/الموضوع بنجاح.");
                            else sendHtmlText(chatId, "⚠️ لم يتم العثور على الأمر المطابق.");
                        }
                    }
                    break;

                case "ADD_RULE":
                    if (message.hasText()) {
                        try (PreparedStatement pst = conn.prepareStatement("INSERT INTO RulesList(RuleText) VALUES(?)")) {
                            pst.setString(1, message.getText().trim());
                            pst.executeUpdate();
                            sendHtmlText(chatId, "✅ تم حفظ القانون الجديد.");
                        }
                    }
                    break;

                case "REMOVE_RULE":
                    if (message.hasText()) {
                        try (PreparedStatement pst = conn.prepareStatement("DELETE FROM RulesList WHERE RuleText LIKE ?")) {
                            pst.setString(1, "%" + message.getText().trim() + "%");
                            int rows = pst.executeUpdate();
                            if(rows > 0) sendHtmlText(chatId, "🗑️ تم حذف القانون بنجاح.");
                            else sendHtmlText(chatId, "⚠️ لم يتم العثور على القانون المطابق.");
                        }
                    }
                    break;

                case "BAN_USER":
                    if (message.hasText()) {
                        long targetId = Long.parseLong(message.getText().trim());
                        String sql = "INSERT INTO BannedMutedUsers(UserId, IsBanned) VALUES(?, 1) ON CONFLICT(UserId) DO UPDATE SET IsBanned=1;";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setLong(1, targetId);
                            pstmt.executeUpdate();
                            sendHtmlText(chatId, "⛔ تم حظر المستخدم ذو الآيدي: <code>" + targetId + "</code> بنجاح.");
                        }
                    }
                    break;

                case "UNBAN_USER":
                    if (message.hasText()) {
                        long targetId = Long.parseLong(message.getText().trim());
                        String sql = "UPDATE BannedMutedUsers SET IsBanned = 0, WarningCount = 0 WHERE UserId = ?";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setLong(1, targetId);
                            pstmt.executeUpdate();
                            sendHtmlText(chatId, "✅ تم إلغاء حظر وتصفير إنذارات المستخدم: <code>" + targetId + "</code>.");
                        }
                    }
                    break;

                case "MUTE_USER":
                    if (message.hasText()) {
                        long targetId = Long.parseLong(message.getText().trim());
                        String sql = "INSERT INTO BannedMutedUsers(UserId, IsMuted) VALUES(?, 1) ON CONFLICT(UserId) DO UPDATE SET IsMuted=1;";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setLong(1, targetId);
                            pstmt.executeUpdate();
                            sendHtmlText(chatId, "🔇 تم كتم المستخدم ذو الآيدي: <code>" + targetId + "</code> بنجاح.");
                        }
                    }
                    break;

                case "UNMUTE_USER":
                    if (message.hasText()) {
                        long targetId = Long.parseLong(message.getText().trim());
                        String sql = "UPDATE BannedMutedUsers SET IsMuted = 0, WarningCount = 0 WHERE UserId = ?";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            pstmt.setLong(1, targetId);
                            pstmt.executeUpdate();
                            sendHtmlText(chatId, "🔊 تم رفع الكتم وتصفير إنذارات المستخدم: <code>" + targetId + "</code>.");
                        }
                    }
                    break;

                case "KICK_USER":
                    if (message.hasText()) {
                        long targetId = Long.parseLong(message.getText().trim());
                        try {
                            BanChatMember ban = new BanChatMember();
                            ban.setChatId(String.valueOf(chatId));
                            ban.setUserId(targetId);
                            execute(ban);
                        } catch (Exception ignored) {}
                        sendHtmlText(chatId, "🚷 تم طرد المستخدم ذو الآيدي (" + targetId + ").");
                    }
                    break;
            }
        } catch (Exception e) {
            sendHtmlText(chatId, "⚠️ حدث خطأ (تأكد من إرسال آيدي صحيح مكون من أرقام).");
        }
    }

    private void sendAdminsMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("🛡️ <b>إدارة المشرفين والمساعدين:</b>\n\nاختر الإجراء المطلوب 👇:");
        message.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btnAddAdmin = new InlineKeyboardButton("➕ إضافة مشرف");
        btnAddAdmin.setCallbackData("admin_add_prompt");
        InlineKeyboardButton btnRemoveAdmin = new InlineKeyboardButton("🗑️ حذف مشرف");
        btnRemoveAdmin.setCallbackData("admin_remove_prompt");
        row1.add(btnAddAdmin);
        row1.add(btnRemoveAdmin);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnListAdmins = new InlineKeyboardButton("📋 عرض قائمة المشرفين");
        btnListAdmins.setCallbackData("list_admins");
        row2.add(btnListAdmins);

        rows.add(row1);
        rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendAdminPanel(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("👑 <b>لوحة تحكم الأدمن:</b>\n\nاختر القسم المطلوب 👇:");
        message.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // الصف الأول: الإحصائيات والإعدادات العامة
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btnStats = new InlineKeyboardButton("📊 الإحصائيات الشاملة");
        btnStats.setCallbackData("admin_stats_menu");
        InlineKeyboardButton btnSettings = new InlineKeyboardButton("⚙️ إعدادات البوت");
        btnSettings.setCallbackData("admin_settings_menu");
        row1.add(btnStats);
        row1.add(btnSettings);

        // الصف الثاني: المحظورين والإذاعة
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnBanned = new InlineKeyboardButton("🚫 المحظورين والتحكم");
        btnBanned.setCallbackData("admin_banned_menu");
        InlineKeyboardButton btnBroadcast = new InlineKeyboardButton("📢 الإذاعة العامة");
        btnBroadcast.setCallbackData("admin_broadcast_menu");
        row2.add(btnBanned);
        row2.add(btnBroadcast);

        // الصف الثالث: إدارة المشرفين (الآدمنز الجدد) 👑
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton btnAdmins = new InlineKeyboardButton("🛡️ إدارة المشرفين");
        btnAdmins.setCallbackData("admin_admins_menu");
        row3.add(btnAdmins);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3); // إضافته إلى القائمة الرئيسية للأزرار

        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendStatsMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("📊 <b>مركز الإحصائيات والتقارير:</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bOverview = new InlineKeyboardButton("📋 عرض الملخص العام"); bOverview.setCallbackData("stats_overview");
        InlineKeyboardButton bWords = new InlineKeyboardButton("🛡️ عرض المحظورات"); bWords.setCallbackData("stats_words_list");
        row1.add(bOverview); row1.add(bWords);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للوحة الرئيسية"); btnBack.setCallbackData("admin_panel");
        row2.add(btnBack);
        rows.add(row1); rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendSettingsMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("⚙️ <b>قسم الإعدادات المتقدمة:</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton sWords = new InlineKeyboardButton("🛡️ إعدادات الكلمات"); sWords.setCallbackData("set_words_menu");
        InlineKeyboardButton sCommands = new InlineKeyboardButton("🤖 إعدادات الأوامر"); sCommands.setCallbackData("set_commands_menu");
        row1.add(sWords); row1.add(sCommands);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton sRules = new InlineKeyboardButton("📜 إعدادات القوانين"); sRules.setCallbackData("set_rules_menu");
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للوحة الرئيسية"); btnBack.setCallbackData("admin_panel");
        row2.add(sRules); row2.add(btnBack);
        rows.add(row1); rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendWordsSubMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("🛡️ <b>إدارة الكلمات المحظورة:</b>");
        message.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // الصف الأول: إضافة كلمة وحذف كلمة
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bAdd = new InlineKeyboardButton("➕ إضافة كلمة");
        bAdd.setCallbackData("word_add_prompt");
        InlineKeyboardButton bRem = new InlineKeyboardButton("🗑️ حذف كلمة");
        bRem.setCallbackData("word_remove_prompt");
        row1.add(bAdd);
        row1.add(bRem);

        // الصف الثاني: إضافة قائمة كاملة دفعة واحدة (جديد 🔥)
        List<InlineKeyboardButton> rowBulk = new ArrayList<>();
        InlineKeyboardButton bBulkAdd = new InlineKeyboardButton("📥 إضافة قائمة كلمات");
        bBulkAdd.setCallbackData("word_bulk_add_prompt");
        rowBulk.add(bBulkAdd);

        // الصف الثالث: عرض الكل والعودة
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton bShow = new InlineKeyboardButton("📋 عرض كل الكلمات");
        bShow.setCallbackData("stats_words_list");
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للإعدادات");
        btnBack.setCallbackData("admin_settings_menu");
        row2.add(bShow);
        row2.add(btnBack);

        rows.add(row1);
        rows.add(rowBulk); // إدراج صف القائمة الجماعية
        rows.add(row2);

        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendCommandsSubMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("🤖 <b>إدارة الأوامر المخصصة:</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bAdd = new InlineKeyboardButton("➕ إضافة أمر"); bAdd.setCallbackData("cmd_add_prompt");
        InlineKeyboardButton bRem = new InlineKeyboardButton("🗑️ حذف أمر"); bRem.setCallbackData("cmd_remove_prompt");
        row1.add(bAdd); row1.add(bRem);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton bShow = new InlineKeyboardButton("📋 عرض الأوامر"); bShow.setCallbackData("stats_commands_list");
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للإعدادات"); btnBack.setCallbackData("admin_settings_menu");
        row2.add(bShow); row2.add(btnBack);
        rows.add(row1); rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendRulesSubMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("📜 <b>إدارة القوانين:</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bAdd = new InlineKeyboardButton("➕ إضافة قانون"); bAdd.setCallbackData("rule_add_prompt");
        InlineKeyboardButton bRem = new InlineKeyboardButton("🗑️ حذف قانون"); bRem.setCallbackData("rule_remove_prompt");
        row1.add(bAdd); row1.add(bRem);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للإعدادات"); btnBack.setCallbackData("admin_settings_menu");
        row2.add(btnBack);
        rows.add(row1); rows.add(row2);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendBannedUsersManagement(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("🚫 <b>إدارة الأعضاء (الحظر، الكتم، والطرد):</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row0 = new ArrayList<>();
        InlineKeyboardButton bList = new InlineKeyboardButton("📋 عرض قائمة الحالات والإنذارات");
        bList.setCallbackData("list_banned_muted_users");
        row0.add(bList);

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bBan = new InlineKeyboardButton("⛔ حظر عضو"); bBan.setCallbackData("ban_user_prompt");
        InlineKeyboardButton bUnban = new InlineKeyboardButton("✅ إلغاء الحظر والإنذارات"); bUnban.setCallbackData("unban_user_prompt");
        row1.add(bBan); row1.add(bUnban);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton bMute = new InlineKeyboardButton("🔇 كتم عضو"); bMute.setCallbackData("mute_user_prompt");
        InlineKeyboardButton bUnmute = new InlineKeyboardButton("🔊 إلغاء الكتم"); bUnmute.setCallbackData("unmute_user_prompt");
        row2.add(bMute); row2.add(bUnmute);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton bKick = new InlineKeyboardButton("🚷 طرد من الجروب"); bKick.setCallbackData("kick_user_prompt");
        row3.add(bKick);

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للوحة الرئيسية"); btnBack.setCallbackData("admin_panel");
        row4.add(btnBack);

        rows.add(row0); rows.add(row1); rows.add(row2); rows.add(row3); rows.add(row4);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendBroadcastMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("📢 <b>مركز الإذاعة العامة الذكية:</b>");
        message.setParseMode("HTML");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton bcAll = new InlineKeyboardButton("🌐 إذاعة للكل"); bcAll.setCallbackData("bc_all");
        row1.add(bcAll);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton bcPrivate = new InlineKeyboardButton("👤 للخاص فقط"); bcPrivate.setCallbackData("bc_private");
        InlineKeyboardButton bcGroups = new InlineKeyboardButton("💬 للجروبات فقط"); bcGroups.setCallbackData("bc_groups");
        row2.add(bcPrivate); row2.add(bcGroups);
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton btnBack = new InlineKeyboardButton("⬅️ العودة للوحة الرئيسية"); btnBack.setCallbackData("admin_panel");
        row3.add(btnBack);
        rows.add(row1); rows.add(row2); rows.add(row3);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    public static void main(String[] args) {
        try {
            // 1. فتح خادم ويب مصغر للاستجابة لمتطلبات Railway وتجنب خطأ 502 Bad Gateway
            String portStr = System.getenv("PORT");
            int port = portStr != null ? Integer.parseInt(portStr) : 8080;

            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                String response = "Bot is running 24/7 successfully!";
                exchange.sendResponseHeaders(200, response.getBytes().length);
                java.io.OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            });
            server.setExecutor(null);
            server.start();
            System.out.println("HTTP Server started on port " + port);

            // 2. تشغيل البوت بالطريقة الطبيعية
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new Main());
            System.out.println("🤖 Bot started successfully with SQLite database & Warning System!");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}