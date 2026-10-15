package com.orderflow.engine.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-37: every error code has a title and a detail in es and en, and the bundles have identical keys. */
class ErrorCatalogI18nTest {

    private static final String BASE = "messages.properties";
    private static final String SPANISH = "messages_es.properties";
    private static final String ENGLISH = "messages_en.properties";
    private static final Pattern ERROR_KEY = Pattern.compile("error\\.([A-Z_]+)\\.(title|detail)");

    @ParameterizedTest
    @ValueSource(strings = {BASE, SPANISH, ENGLISH})
    void everyErrorCodeHasATitleAndADetail(String bundle) throws IOException {
        Map<String, String> messages = load(bundle);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messages.get(code.titleKey())).as("%s in %s", code.titleKey(), bundle).isNotBlank();
            assertThat(messages.get(code.detailKey())).as("%s in %s", code.detailKey(), bundle).isNotBlank();
        }
    }

    @Test
    void theBundlesHaveIdenticalKeys() throws IOException {
        Set<String> spanishKeys = load(SPANISH).keySet();

        assertThat(load(BASE).keySet()).as("keys of %s", BASE).isEqualTo(spanishKeys);
        assertThat(load(ENGLISH).keySet()).as("keys of %s", ENGLISH).isEqualTo(spanishKeys);
    }

    @ParameterizedTest
    @ValueSource(strings = {BASE, SPANISH, ENGLISH})
    void everyKeyBelongsToAnErrorCode(String bundle) throws IOException {
        Set<String> codes = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

        for (String key : load(bundle).keySet()) {
            Matcher matcher = ERROR_KEY.matcher(key);
            assertThat(matcher.matches()).as("key %s in %s is an error title or detail", key, bundle).isTrue();
            assertThat(codes).as("key %s in %s names a catalog code", key, bundle).contains(matcher.group(1));
        }
    }

    @Test
    void theBaseBundleIsSpanish() throws IOException {
        assertThat(load(BASE)).as("%s repeats %s", BASE, SPANISH).isEqualTo(load(SPANISH));
    }

    @Test
    void theEnglishBundleIsATranslation() throws IOException {
        Map<String, String> spanish = load(SPANISH);
        Map<String, String> english = load(ENGLISH);

        for (Map.Entry<String, String> entry : english.entrySet()) {
            assertThat(entry.getValue()).as("%s is translated", entry.getKey()).isNotEqualTo(spanish.get(entry.getKey()));
        }
    }

    /** Decodes strictly, so a bundle saved in any encoding other than UTF-8 fails instead of loading garbled. */
    private static Map<String, String> load(String bundle) throws IOException {
        try (InputStream in = ErrorCatalogI18nTest.class.getResourceAsStream("/i18n/" + bundle)) {
            assertThat(in).as("bundle i18n/%s exists", bundle).isNotNull();
            String text = decodeUtf8(in.readAllBytes(), bundle);
            Properties properties = new Properties();
            properties.load(new StringReader(text));
            Map<String, String> messages = new TreeMap<>();
            properties.stringPropertyNames().forEach(key -> messages.put(key, properties.getProperty(key)));
            return messages;
        }
    }

    private static String decodeUtf8(byte[] bytes, String bundle) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError("bundle i18n/" + bundle + " is not valid UTF-8", e);
        }
    }
}
