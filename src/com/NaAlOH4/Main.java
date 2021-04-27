package com.NaAlOH4;

import com.google.gson.*;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.cli.*;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@ParametersAreNonnullByDefault

public class Main {

    public static final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    public static final String URL_HEAD = "https://api.telegram.org/bot";
    public static final Options options = new Options()
            .addOption("h", "help", false, "do nothing but print usage.")
            .addOption("t", "token", true, "token from bot father")
            .addOption("g", "group", true, "group id or @Username(ignore case).\n" +
                    "bot will only works in these group.\ncan be a regex(case sensitive), or split by \",\" or \"，\"")
            .addOption("i", "ignore", true, "a white list of uid or @Username(ignore case).\n" +
                    "bot will do nothing to their message. \ncan be a regex(case sensitive), or split by \",\" or \"，\"")
            .addOption(null, "keep-service-message", false, "keep service message like \"xxx joined group\"")
            .addOption("v", "verbose", false, "show more information.");

    private static final OkHttpClient updateClient = new OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build();
    private static final OkHttpClient client = new OkHttpClient.Builder().build();

    private static String token;
    private static boolean verbose = false;

    private static final Map<String, Long> latestCounts = new HashMap<>();
    private static final Map<String, String> latestCounterIDs = new HashMap<>();
    private static final Map<String, Boolean> rollBackTags = new HashMap<>();

    public static void main(String[] args) {

        CommandLine commandLine;
        try {
            commandLine = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            throw new WTFException();
        }

        if (commandLine.hasOption("h")) {
            printHelp();
            return;
        }


        final String groupArg = commandLine.getOptionValue("g");
        token = commandLine.getOptionValue("t");
        final String whiteUser = commandLine.getOptionValue("i");
        final boolean deleteServiceMessage = !commandLine.hasOption("keep-service-message");

        verbose = commandLine.hasOption("v");


        if (groupArg == null || token == null) {
            System.err.println("missing arg" + ((groupArg == null && token == null) ? "s" : "") + ": " +
                    ((groupArg == null) ? "group" : "") +
                    ((groupArg == null && token == null) ? " and " : " ") +
                    ((token == null) ? "token" : "") + "."
            );
            System.exit(127);
            return; // this line will never be executed.
        }

        Predicate<JsonObject> checkGroupInclude = getCheckFunctionByArgument(groupArg);

        Predicate<JsonObject> checkUserWhitelistInclude = whiteUser == null ?
                anything -> false :
                getCheckFunctionByArgument(whiteUser);


        int offset = 0;
        JsonArray result;
        // update loop
        while (true) {

            if (verbose) {
                System.out.println("getting Updates...");
            }
            try {
                // get Updates
                Response response = updateClient.newCall(
                        new Request.Builder()
                                .url(URL_HEAD + token + "/getUpdates")
                                .post(
                                        new FormBody.Builder()
                                                .add("timeout", "110")
                                                .add("offset", String.valueOf(offset + 1))
                                                .build()
                                )
                                .build()
                ).execute();
                JsonObject responseBody = new Gson().fromJson(response.body().string(), JsonObject.class);
                if (verbose) {
                    System.out.println(prettyGson.toJson(responseBody));
                }
                if (!responseBody.get("ok").getAsBoolean()) {
                    System.err.println(responseBody);
                    continue;
                }
                result = responseBody.get("result").getAsJsonArray();

                // handle Updates

                checkUpdateLoop: for (JsonElement jsonElement : result) {
                    JsonObject jsonObject = (JsonObject) jsonElement;
                    offset = Math.max(jsonObject.get("update_id").getAsInt(), offset);

                    JsonObject message = jsonObject.getAsJsonObject("message");

                    if (message != null) {
                        if (checkGroupInclude.test(message.getAsJsonObject("chat"))) {

                            // check service message
                            if (message.has("new_chat_members") || message.has("left_chat_member")) {
                                if (deleteServiceMessage) deleteMessage(message);
                                if (verbose) System.out.println(prettyGson.toJson(message));
                                continue;
                            }

                            // check whiteList message
                            if (checkUserWhitelistInclude.test(message.getAsJsonObject("from"))) {
                                // if count
                                if (!message.has("text")) continue;
                                String text = message.get("text").getAsString();
                                if (text.startsWith("0x")) text = text.substring(2);
                                long currentCount;
                                try {
                                    currentCount = Long.parseLong(text.toLowerCase(), 16);
                                } catch (NumberFormatException ignore) {
                                    continue;
                                }
                                String chatID = message.getAsJsonObject("chat").get("id").getAsString();
                                String currentCounterID = message.getAsJsonObject("from").get("id").getAsString();

                                // check if group init
                                if (latestCounts.containsKey(chatID)) {

                                    if (checkDelta(currentCount, chatID)) {
                                        // is normal  count, ignore.
                                        latestCounts.put(chatID, currentCount);
                                        latestCounterIDs.put(chatID, currentCounterID);
                                        if (verbose) {
                                            System.out.println(chatID + " counted to 0x" + Long.toString(currentCount, 16));
                                            System.out.println("latest Counter id: " + currentCounterID + " (whitelist user)");
                                        }
                                    } else {
                                        // delete wrong count
                                        deleteMessage(message);
                                        if (verbose) {
                                            System.err.println("whitelist user " + message.getAsJsonObject("from").get("id").getAsString() + " counts wrong!");
                                            System.err.println("latest is 0x" + Long.toString(latestCounts.get(chatID), 16) + " but he count 0x" + Long.toString(currentCount, 16));
                                        }
                                    }
                                } else {
                                    //init
                                    if (verbose)
                                        System.out.println("(whitelist user) inited group " + chatID + ", count to 0x" + Long.toString(currentCount, 16));
                                    latestCounts.put(chatID, currentCount);
                                    latestCounterIDs.put(chatID, message.getAsJsonObject("from").get("id").getAsString());
                                }
                                continue;
                            }

                            // Easter egg: @xierch can send "啊啊啊" at ANY times
                            if (
                                    message.getAsJsonObject("from").has("username") &&
                                            message.getAsJsonObject("from").get("username").getAsString().equals("xierch") &&
                                            message.has("text") &&
                                            message.get("text").getAsString().matches("啊*")
                            ) {
                                continue;
                            }

                            // check is normal text message
                            if (message.has("text") && !message.has("reply_to_message")) {

                                // delete link, mention, and strikethrough
                                if(message.has("entities")){
                                    JsonArray entities = message.getAsJsonArray("entities");
                                    for (JsonElement json:entities) {
                                        for (String s: new String[]{"mention", "text_link", "strikethrough"}) {
                                            if(s.equals(((JsonObject) json).get("type").getAsString())){
                                                deleteMessage(message);
                                                continue checkUpdateLoop;
                                            }
                                        }
                                    }
                                }

                                String text = message.get("text").getAsString();

                                // allow "0x"
                                if (text.startsWith("0x")) text = text.substring(2);

                                // 0086 or +0086 is not allowed. see: https://t.me/CountTo0xffffffff/252
                                if(text.startsWith("0")||text.startsWith("+")){
                                    deleteMessage(message);
                                    continue;
                                }
                                long currentCount;
                                try {
                                    currentCount = Long.parseLong(text.toLowerCase(), 16);
                                } catch (NumberFormatException e) {
                                    // not a number, delete.
                                    deleteMessage(message);
                                    continue;
                                }


                                String currentCounterID = message.getAsJsonObject("from").get("id").getAsString();
                                String chatID = message.getAsJsonObject("chat").get("id").getAsString();

                                if (checkDelta(currentCount, chatID) && !currentCounterID.equals(latestCounterIDs.get(chatID))) {
                                    latestCounts.put(chatID, currentCount);
                                    latestCounterIDs.put(chatID, currentCounterID);
                                    if (verbose) {
                                        System.out.println(chatID + "counted to 0x" + Long.toString(currentCount, 16));
                                        System.out.println("latest Counter id: " + currentCounterID);
                                    }
                                } else {
                                    deleteMessage(message);
                                }
                            } else {
                                // delete non-text message and replys
                                deleteMessage(message);
                            }

                        }
                    } else {
                        if (jsonObject.has("edited_message")) {
                            JsonObject editedMessage = jsonObject.getAsJsonObject("edited_message");
                            if (checkGroupInclude.test(editedMessage.getAsJsonObject("chat"))
                                    && !checkUserWhitelistInclude.test(editedMessage.getAsJsonObject("from"))) {
                                deleteMessage(editedMessage);
                                banUser(editedMessage);
                                rollBackTags.put(editedMessage.getAsJsonObject("chat").get("id").getAsString(), true);
                            }
                        }
                    }
                }

            } catch (IOException | NullPointerException e) {
                if (verbose) {
                    e.printStackTrace();
                }
            }

        }
    }

    public static void printHelp() {
        HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.setOptionComparator(null);
        helpFormatter.printHelp(" ", options);
    }

    private static boolean isUsername(String string) {
        return string.toLowerCase().matches("@?[a-z][a-z0-9_]{4,31}") && !Pattern.compile("__").matcher(string).find();
    }

    private static boolean isNumber(String string) {
        return string.matches("-?[0-9]*");
    }

    /**
     * split a string by "," or "，"
     * any of them must be
     *
     * @return null if not a list.
     */

    private static @Nullable
    String[] parseList(String string) {
        if (!string.toLowerCase().matches("[@a-z0-9_,，]*")) return null;
        final String[] keys = string.split("[,，]");
        for (String key : keys) {
            if (!isNumber(key) && !isUsername(key)) {
                return null;
            }
        }
        return keys;
    }


    private static Predicate<JsonObject> getCheckFunctionByArgument(String arg) {
        if (isNumber(arg)) return json -> arg.equals(json.get("id").getAsString());
        if (isUsername(arg)) {
            final String username = arg.substring((arg.startsWith("@")) ? 1 : 0);
            return json -> username.equalsIgnoreCase(json.get("username").getAsString());
        }
        final String[] keys = parseList(arg);
        if (keys != null) {
            final LinkedList<String> UIDs = new LinkedList<>();
            final LinkedList<String> usernames = new LinkedList<>();
            for (String key : keys) {
                if (isNumber(key)) UIDs.add(key);
                else if (isUsername(key)) usernames.add(
                        key.startsWith("@") ? key.substring(1) : key
                );
                else throw new WTFException();
            }

            return json -> {
                for (String uid : UIDs) {
                    if (uid.equals(json.get("id").getAsString())) return true;
                }
                if (json.has("username"))
                    for (String username : usernames) {
                        if (username.equals(json.get("username").getAsString())) return true;
                    }
                return false;
            };
        }

        final Pattern pattern = Pattern.compile(arg);
        return json -> {
            if (pattern.matcher(json.get("id").getAsString()).matches()) return true;
            if (!json.has("username")) return false;
            return pattern.matcher(json.get("username").getAsString()).matches();
        };
    }

    private static boolean checkDelta(long current, String chatID) {
        if (!latestCounts.containsKey(chatID)) return false;
        long delta = current - latestCounts.get(chatID);

        if (delta == 1) return true;
        if (delta == 0 && rollBackTags.containsKey(chatID) && rollBackTags.get(chatID)) {
            rollBackTags.put(chatID, false);
            return true;
        }
        return false;
    }

    private static void deleteMessage(JsonObject message) {
        new Thread(() -> {
            boolean deleted = false;
            for (int i = 1; i <= 3; i++) {
                JsonObject deleteResponseJson = null;
                try {
                    Response deleteResponse = client.newCall(
                            new Request.Builder()
                                    .url(URL_HEAD + token + "/deleteMessage")
                                    .post(
                                            new FormBody.Builder()
                                                    .add("chat_id", message.getAsJsonObject("chat").get("id").getAsString())
                                                    .add("message_id", message.get("message_id").getAsString())
                                                    .build()
                                    )
                                    .build()
                    ).execute();
                    deleteResponseJson = new Gson().fromJson(deleteResponse.body().string(), JsonObject.class);
                    deleteResponse.close();
                    if (deleteResponseJson.has("ok") &&
                            deleteResponseJson.get("ok").getAsBoolean()) {
                        deleted = true;
                        break;
                    } else throw new IOException();
                } catch (IOException | NullPointerException | JsonSyntaxException e) {
                    if (verbose) {
                        System.err.println("trying to delete message failed(" + i + "/3): ");
                        System.err.println(prettyGson.toJson(message));
                        if (deleteResponseJson != null) {
                            System.err.println("return: ");
                            System.err.println(prettyGson.toJson(deleteResponseJson));
                        }
                    }
                }
            }
            if (!deleted) {
                System.err.println("trying to delete message failed 3 times: ");
                System.err.println(prettyGson.toJson(message));
            }
        }).start();
    }

    private static void banUser(JsonObject message) {
        new Thread(() -> {
            boolean banned = false;
            for (int i = 1; i <= 3; i++) {
                JsonObject responseJson = null;
                try {

                    System.out.println(message.getAsJsonObject("chat").get("id").getAsString());
                    Response response = client.newCall(
                            new Request.Builder()
                                    .url(URL_HEAD + token + "/restrictChatMember")
                                    .post(
                                            new FormBody.Builder()
                                                    .add("chat_id", message.getAsJsonObject("chat").get("id").getAsString())
                                                    .add("user_id", message.getAsJsonObject("from").get("id").getAsString())
                                                    .add("permissions", "{\"can_send_messages\":false}")
                                                    .build()
                                    )
                                    .build()
                    ).execute();
                    responseJson = new Gson().fromJson(response.body().string(), JsonObject.class);
                    response.close();
                    if (responseJson.has("ok") &&
                            responseJson.get("ok").getAsBoolean()) {
                        banned = true;
                        break;
                    } else throw new IOException();
                } catch (IOException | NullPointerException | JsonSyntaxException e) {
                    if (verbose) {
                        System.err.println("trying to ban user failed(" + i + "/3): ");
                        System.err.println(prettyGson.toJson(message));
                        if (responseJson != null) {
                            System.err.println("return: ");
                            System.err.println(prettyGson.toJson(responseJson));
                        }
                    }
                }
            }
            if (!banned) {
                System.err.println("trying to ban user failed 3 times: ");
                System.err.println(prettyGson.toJson(message));
            }
        }).start();
    }
}