package com.example.recommend.chat.config;

import com.example.recommend.chat.client.AiClient;
import com.example.recommend.chat.client.OpenAiClient;
import com.openai.client.OpenAIClient;
import com.openai.core.RequestOptions;
import com.openai.models.responses.ResponseCreateParams;
import java.util.*;
import static org.mockito.Mockito.*;

/** Test-only recorder for fixed synthetic fixtures. No inputs, headers, SDK errors or production logging. */
final class SyntheticClassifierTrace implements AutoCloseable {
    private final OpenAIClient sdk;
    final AiClient client;
    final List<Map<String, Object>> items = new ArrayList<>();

    SyntheticClassifierTrace(AiProperties properties, OpenAIClient sdk) {
        this.sdk = sdk;
        var intercepted = mock(OpenAIClient.class); var resource = spy(sdk.responses());
        when(intercepted.responses()).thenReturn(resource);
        doAnswer(call -> {
            var item = new LinkedHashMap<String, Object>(); item.put("status", "no_response"); items.add(item);
            var response = sdk.responses().create((ResponseCreateParams) call.getArgument(0), (RequestOptions) call.getArgument(1));
            item.put("status", response.status().map(Object::toString).orElse("missing"));
            response.incompleteDetails().ifPresent(d -> item.put("incompleteReason", d.reason().map(Object::toString).orElse("missing")));
            response.usage().ifPresent(u -> { item.put("inputTokens", u.inputTokens()); item.put("outputTokens", u.outputTokens()); });
            var output = new StringBuilder();
            response.output().forEach(i -> i.message().ifPresent(m -> m.content().forEach(c -> c.outputText().ifPresent(t -> output.append(t.text())))));
            item.put("outputChars", output.length());
            if (!output.isEmpty() && output.length() <= 16000) item.put("syntheticOutput", output.toString());
            else if (output.length() > 16000) item.put("outputOmitted", true);
            return response;
        }).when(resource).create(any(ResponseCreateParams.class), any(RequestOptions.class));
        client = new OpenAiClient(intercepted, properties);
    }

    @Override public void close() { sdk.close(); }
}
