package com.copymylife.copymylife.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Post photos may be private; they are only ever sent by GET /posts/{id}/image.
    static final String PRIVATE_FOLDER = "posts";

    private final String uploadDir;

    public WebConfig(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A missing folder's URI has no trailing slash, and a resource location needs one.
        String location = Path.of(uploadDir).toAbsolutePath().toUri().toString().replaceAll("/*$", "/");

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource resourceLocation) throws IOException {
                        // Checked without case: on a case-insensitive disk "POSTS/x.png" is the same file.
                        if (isPrivate(resourcePath)) {
                            return null;
                        }
                        return super.getResource(resourcePath, resourceLocation);
                    }
                });
    }

    static boolean isPrivate(String resourcePath) {
        String path = resourcePath.replace('\\', '/').replaceFirst("^[/.]+", "").toLowerCase(Locale.ROOT);
        return path.equals(PRIVATE_FOLDER) || path.startsWith(PRIVATE_FOLDER + "/");
    }
}
