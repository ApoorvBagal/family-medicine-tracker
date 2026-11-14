package com.familymeds.shared.web;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Caching for the PWA's files:
 * <ul>
 *   <li>{@code /assets/**}: file names contain a content hash (index-BEMyR18V.js), so they can be cached
 *       for a year; a new deploy produces new names.</li>
 *   <li>Everything else ({@code index.html}, manifest, icons): {@code no-cache}, i.e. always revalidated,
 *       so phones pick up a new version right after a deploy
 *       ({@code spring.web.resources.cache.cachecontrol.no-cache} in application.yml).</li>
 * </ul>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
