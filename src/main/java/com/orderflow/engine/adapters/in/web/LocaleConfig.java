package com.orderflow.engine.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Picks the language of the user text from Accept-Language. The supported languages are the
 * bundles found next to the base bundle, so adding a language means adding one file (SPEC §9).
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

    private static final String BUNDLE_SUFFIX = ".properties";

    @Bean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver(@Value("${app.i18n.default-locale}") Locale defaultLocale,
            @Value("${spring.messages.basename}") String basename) throws IOException {
        return new AcceptLanguageLocaleResolver(bundleLocales(basename), defaultLocale);
    }

    private static List<Locale> bundleLocales(String basename) throws IOException {
        String prefix = StringUtils.getFilename(basename) + "_";
        Resource[] bundles = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + basename + "_*" + BUNDLE_SUFFIX);
        return Arrays.stream(bundles)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .map(name -> name.substring(prefix.length(), name.length() - BUNDLE_SUFFIX.length()))
                .map(StringUtils::parseLocale)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Reads only the header, never the servlet container's locale: an empty or malformed header
     * would otherwise fall back to the JVM's default language instead of the configured one.
     */
    static final class AcceptLanguageLocaleResolver implements LocaleResolver {

        private final List<Locale> supported;
        private final Locale defaultLocale;

        AcceptLanguageLocaleResolver(List<Locale> supported, Locale defaultLocale) {
            this.supported = supported;
            this.defaultLocale = defaultLocale;
        }

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
            if (!StringUtils.hasText(header)) {
                return defaultLocale;
            }
            try {
                Locale match = Locale.lookup(Locale.LanguageRange.parse(header), supported);
                return match != null ? match : defaultLocale;
            } catch (IllegalArgumentException malformedHeader) {
                return defaultLocale;
            }
        }

        @Override
        public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            throw new UnsupportedOperationException("The locale follows the Accept-Language header of each request");
        }
    }
}
