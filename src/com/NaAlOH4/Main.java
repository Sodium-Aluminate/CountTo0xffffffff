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
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@ParametersAreNonnullByDefault

public class Main {
    public static final String URL_HEAD = "https://api.telegram.org/bot";
    public static final Options options = new Options()
            .addOption("h", "help", false, "do nothing but print usage.")
            .addOption("t", "token", true, "token from bot father")
            .addOption("g", "group", true, "group id or @Username(ignore case).\n"+
                    "bot will only works in these group.\ncan be a regex(case sensitive), or split by \",\" or \"，\"")
            .addOption("c","continue",true,"continue count from a number(last sent).")
            .addOption("i", "ignore", true, "a white list of uid or @Username(ignore case).\n"+
                    "bot will do nothing to their message. \ncan be a regex(case sensitive), or split by \",\" or \"，\"")
            .addOption(null,"keep-service-message",false, "keep service message like \"xxx joined group\"")
            .addOption("v", "verbose", false, "show more information.");

    private static final OkHttpClient updateClient = new OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build();
    private static final OkHttpClient client = new OkHttpClient.Builder().build();

    private static String token;
    private static boolean verbose = false;

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
        final String continueNumber = commandLine.getOptionValue("c");
        long latestCount = continueNumber==null ? -1 : Long.parseLong(continueNumber, 16);
        @Nullable String latestCounterID = null;
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

            if(verbose){
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
                                                .add("offset", String.valueOf(offset+1))
                                                .build()
                                )
                                .build()
                ).execute();
                JsonObject responseBody = new Gson().fromJson(response.body().string(), JsonObject.class);
                if(verbose){
                    System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(responseBody));
                }
                if (!responseBody.get("ok").getAsBoolean()) {
                    System.err.println(responseBody);
                    continue;
                }
                result = responseBody.get("result").getAsJsonArray();

                // handle Updates

                for (JsonElement jsonElement : result) {
                    JsonObject jsonObject = (JsonObject) jsonElement;
                    offset = Math.max(jsonObject.get("update_id").getAsInt(), offset);


                    JsonObject message = jsonObject.getAsJsonObject("message");

                    if(message!=null) {
                        if (checkGroupInclude.test(message.getAsJsonObject("chat"))) {

                            boolean deleteMessage = true;

                            if (message.has("new_chat_members") || message.has("left_chat_member")) {
                                deleteMessage = deleteServiceMessage;
                            } else if (message.has("text") && !message.has("reply_to_message")) {
                                try {
                                    String text = message.get("text").getAsString();
                                    if(text.startsWith("0x"))text = text.substring(2);
                                    long currentCount = Long.parseLong(text, 16);
                                    String currentCounterID = message.getAsJsonObject("from").get("id").getAsString();
                                    if (currentCount - latestCount == 1 && !currentCounterID.equals(latestCounterID)) {
                                        latestCount++;
                                        latestCounterID = currentCounterID;
                                        deleteMessage = false;
                                        if(verbose){
                                            System.out.println("counted to 0x"+ Long.toString(latestCount,16));
                                            System.out.println("latest Counter id: "+latestCounterID);
                                        }
                                    }
                                } catch (NumberFormatException ignore) {
                                }
                            }

                            if (checkUserWhitelistInclude.test(message.getAsJsonObject("from")))
                                deleteMessage = false;

                            if (deleteMessage) {
                                deleteMessage(message);
                            }
                        }
                    }else {
                        if (jsonObject.has("edited_message")){
                            JsonObject editedMessage = jsonObject.getAsJsonObject("edited_message");
                            if (checkGroupInclude.test(editedMessage.getAsJsonObject("chat"))
                                    && !checkUserWhitelistInclude.test(editedMessage.getAsJsonObject("from"))) {
                                deleteMessage(editedMessage);
                                banUser(editedMessage);
                            }
                        }
                    }
                }

            } catch (IOException | NullPointerException e) {
                if(verbose){
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
     * @return null if not a list.
     */

    private static @Nullable String[] parseList(String string) {
        if (!string.toLowerCase().matches("[a-z0-9_,，]")) return null;
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
        if(keys != null) {
            final LinkedList<String> UIDs = new LinkedList<>();
            final LinkedList<String> usernames = new LinkedList<>();
            for (String key : keys) {
                if (isNumber(key)) UIDs.add(key);
                else if (isUsername(key)) usernames.add(key);
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

    private static void deleteMessage(JsonObject message){
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
                        System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(message));
                        if (deleteResponseJson != null) {
                            System.err.println("return: ");
                            System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(deleteResponseJson));
                        }
                    }
                }
            }
            if (!deleted) {
                System.err.println("trying to delete message failed 3 times: ");
                System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(message));
            }
        }).start();
    }

    private static void banUser(JsonObject message){
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
                        System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(message));
                        if (responseJson != null) {
                            System.err.println("return: ");
                            System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(responseJson));
                        }
                    }
                }
            }
            if (!banned) {
                System.err.println("trying to ban user failed 3 times: ");
                System.err.println(new GsonBuilder().setPrettyPrinting().create().toJson(message));
            }
        }).start();
    }

}