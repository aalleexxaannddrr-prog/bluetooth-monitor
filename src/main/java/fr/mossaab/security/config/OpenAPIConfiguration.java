package fr.mossaab.security.config;


import fr.mossaab.security.handlers.ErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурационный класс OpenAPIConfiguration для настройки OpenAPI и Swagger.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                contact = @Contact(
                        name = "Alexandr",
                        email = "kichmarev@list.ru"
                ),
                title = "GoMind API",
                description = """
                        <strong>API для викторины.</strong>
                        
                        Приложение "Go Mind" - это викторина, в которой можно заработать своим умом.
                        Отвечайте на вопросы быстрее остальных и получайте бонусы.
                        
                        <strong>Шаги для скачивания OpenAPI спецификации:</strong>
                        1. Перейдите к файлу спецификации OpenAPI:
                            http://158.160.138.117:8081/v3/api-docs
                        2. Загрузка спецификации:
                            Откроется JSON-файл с описанием всех ваших API.
                        3. Чтобы его скачать, щелкните правой кнопкой мыши на странице и выберите "Сохранить как...", затем сохраните файл как .json.
                        4.  Импорт файла в Postman:
                            Откройте Postman и нажмите "Import".
                            Выберите скачанный файл .json.
                            После этого Postman автоматически создаст коллекцию с запросами.
                                               
                        Теперь у вас в Postman будут все запросы API, описанные в спецификации.
                        """,
                version = "0.0.1-SNAPSHOT"
        ),
        servers = {},  // Placeholder, will be added dynamically
        security = {
                @SecurityRequirement(
                        name = "bearerAuth"
                )
        }
)
@SecurityScheme(
        name = "bearerAuth",
        description = "Описание аутентификации JWT",
        scheme = "bearer",
        type = SecuritySchemeType.HTTP,
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER
)
public class OpenAPIConfiguration {


    @Bean
    public OpenApiCustomizer openApiCustomizer() {
        return openApi -> openApi.addServersItem(new io.swagger.v3.oas.models.servers.Server().url("http://localhost:8080"));
    }

    /**
     * Создает OpenApiCustomizer для настройки схемы модели ErrorResponse.
     *
     * @return OpenApiCustomizer для настройки схемы модели ErrorResponse
     */
    @Bean
    public OpenApiCustomizer schemaCustomizer() {
        // Разрешение схемы модели ErrorResponse
        ResolvedSchema resolvedSchema = ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ErrorResponse.class));
        // Создание и настройка OpenApiCustomizer для установки схемы модели ErrorResponse
        return openApi -> openApi
                .schema(resolvedSchema.schema.getName(), resolvedSchema.schema);
    }
}