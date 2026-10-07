package dev.dsh.pocket;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Arrays;
import java.util.List;

/** Runs against a loopback fake API, never the user's saved connection. */
public class ProviderSmokeInstrumentation extends Instrumentation {
    private boolean phoneTest;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); phoneTest = "true".equals(arguments.getString("phone")); start(); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private EngineSettings settings(String protocol, String url, String key) {
        return new EngineSettings("pocket-test-model", "unused", key, url, protocol, true, 131072, 8192, protocol.equals("openai-responses") ? "high" : "low", false);
    }
    @Override public void onStart() {
        if (phoneTest) {
            Bundle output = new Bundle();
            try { output.putString("result", PhoneSmoke.run(this)); finish(Activity.RESULT_OK, output); }
            catch (Throwable e) { output.putString("error", android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, output); }
            return;
        }
        Bundle result = new Bundle();
        Context target = getTargetContext();
        Context isolated = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("provider-smoke-isolated", mode);
            }
        };
        try {
            String[][] endpoints = {
                {"https://gateway.example", "https://gateway.example/v1/chat/completions"},
                {"https://gateway.example/v1/", "https://gateway.example/v1/chat/completions"},
                {"https://gateway.example/v1/chat/completions/", "https://gateway.example/v1/chat/completions"},
                {"https://gateway.example/chat/completions", "https://gateway.example/chat/completions"},
                {"https://gateway.example/prefix/responses", "https://gateway.example/prefix/chat/completions"}
            };
            for (String[] row : endpoints) require(ProviderEndpoint.INSTANCE.request(settings("openai-chat", row[0], "fake")).equals(row[1]), "Endpoint normalization mismatch");
            require(ProviderEndpoint.INSTANCE.request(settings("deepseek-messages", "https://gateway.example/v1", "fake")).equals("https://gateway.example/v1/messages"), "Messages v1 duplicated");
            ProviderClient client = new ProviderClient();
            EngineSettings chat = settings("openai-chat", "http://127.0.0.1:18766/v1/chat/completions", "pocket-smoke-fake-key");
            List<String> models = client.models(chat);
            require(models.size() == 2 && models.contains("pocket-test-model"), "Model discovery failed");
            require(client.test(chat).contains("pocket-test-model"), "Chat request failed");
            require(client.test(settings("openai-responses", "http://127.0.0.1:18766/v1/responses", "pocket-smoke-fake-key")).contains("pocket-test-model"), "Responses request failed");
            require(client.test(settings("deepseek-messages", "http://127.0.0.1:18766/v1/messages", "pocket-smoke-fake-key")).contains("pocket-test-model"), "Messages request failed");
            try {
                client.models(settings("openai-chat", "http://127.0.0.1:18766/v1", "secret-bad-test-key"));
                throw new AssertionError("Invalid credential accepted");
            } catch (IllegalStateException error) {
                require(error.getMessage().contains("401") && !error.getMessage().contains("secret-bad-test-key"), "Credential error must be redacted");
            }
            Secrets secrets = new Secrets(isolated);
            ApiPreset a = new ApiPreset("test-a", "Chat preset", chat);
            ApiPreset b = new ApiPreset("test-b", "Responses preset", settings("openai-responses", "http://127.0.0.1:18766/v1", "pocket-smoke-fake-key"));
            secrets.savePresets(a.getId(), Arrays.asList(a, b));
            require(new Secrets(isolated).presets().getSecond().size() == 2, "Multiple presets were not restored");
            secrets.savePresets(b.getId(), Arrays.asList(a, b));
            require(new Secrets(isolated).settings().getProtocol().equals("openai-responses"), "Active preset was not persisted");
            require(new Secrets(isolated).settings().getReasoningEffort().equals("high"), "Reasoning effort was not persisted");
            for (Object value : isolated.getSharedPreferences("pocket-private", Context.MODE_PRIVATE).getAll().values())
                require(!value.toString().contains("pocket-smoke-fake-key") && !value.toString().contains("http://127.0.0.1"), "Preset leaked plaintext");
            result.putString("stream", "PASS: native endpoint normalization, model discovery, Chat/Responses/Messages requests, 401 redaction, encrypted multiple presets and persisted selection.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally { isolated.getSharedPreferences("pocket-private", Context.MODE_PRIVATE).edit().clear().commit(); }
    }
}
