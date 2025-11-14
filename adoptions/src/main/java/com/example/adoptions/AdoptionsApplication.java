package com.example.adoptions;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authorization.EnableMultiFactorAuthentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.registry.ImportHttpServices;

import javax.sql.DataSource;
import java.security.Principal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Import(MyBeanRegistrar.class)
@EnableResilientMethods
@ImportHttpServices(CatFactsClient.class)
@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }
}

@Controller
@ResponseBody
class MeController {

    @GetMapping("/")
    Map<String, String> me(Principal principal) {
        return Map.of("name", principal.getName());
    }

}

@EnableMultiFactorAuthentication( authorities = {
        FactorGrantedAuthority.OTT_AUTHORITY ,
        FactorGrantedAuthority.PASSWORD_AUTHORITY
})
@Configuration
class SecurityConfiguration {

    @Bean
    JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource) {
        var users = new JdbcUserDetailsManager(dataSource);
        users.setEnableUpdatePassword(true);
        return users;
    }

    @Bean
    Customizer<HttpSecurity> httpSecurityCustomizer() {
        return httpSecurity -> httpSecurity
                .webAuthn(w -> w
                        .rpId("localhost")
                        .rpName("bootiful")
                        .allowedOrigins("http://localhost:8080")
                )
                .oneTimeTokenLogin(ott -> ott
                        .tokenGenerationSuccessHandler((request, response, oneTimeToken) -> {
                            response.getWriter().println("you've got console mail!");
                            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
                            IO.println("please go to http://localhost:8080/login/ott?token=" + oneTimeToken.getTokenValue());
                        }));
    }

}


class MyBeanRegistrar implements BeanRegistrar {

    @Override
    public void register(@NonNull BeanRegistry registry, @NonNull Environment env) {

        for (var locales : "en,fr,ch,zh".split(","))
            registry.registerBean(LocaleCart.class, spec -> spec
                    .supplier(supplierContext -> new LocaleCart(supplierContext.bean(Environment.class), locales)));

    }
}


class LocaleCart {

    private final String locale;

    LocaleCart() {
        this.locale = Locale.ROOT.getDisplayName();
    }

    LocaleCart(Environment e, String locale) {
        this.locale = locale;
        IO.println("registered " + this.locale);
    }
}

@Configuration
class MyConfiguration {

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

}

interface DogRepository extends ListCrudRepository<@NonNull Dog, @NonNull Integer> {
}

record Dog(@Id int id, String name, String owner, String description) {
}

@Controller
@ResponseBody
class DogAdoptionsController {

    private final DogRepository dogRepository;

    DogAdoptionsController(DogRepository dogRepository) {
        this.dogRepository = dogRepository;
    }

    @PostMapping("/dogs/{dogId}/adoptions")
    void adopt(@PathVariable int dogId, @RequestParam String owner) {
        this.dogRepository.findById(dogId).ifPresent(dog -> {
            var updated = this.dogRepository
                    .save(new Dog(dog.id(), dog.name(), owner, dog.description()));
            IO.println("adopted " + updated);
        });
    }
}

@Controller
@ResponseBody
class AnimalsController {

    private final DogRepository dogRepository;

    AnimalsController(DogRepository dogRepository) {
        this.dogRepository = dogRepository;
    }

    @GetMapping(value = "/dogs", version = "1.1")
    Collection<Dog> findAll() {
        return this.dogRepository.findAll();
    }

    @GetMapping(value = "/dogs", version = "1.0")
    Collection<Map<String, Object>> dogs() {
        return this.dogRepository.findAll()
                .stream()
                .map(dog -> Map.of("id", (Object) dog.id(), "dogName", (Object) dog.name()))
                .toList();
    }

}

// https://www.catfacts.net/api

record CatFact(String fact) {
}

record CatFacts(Collection<CatFact> facts) {
}

interface CatFactsClient {

    @GetExchange("https://www.catfacts.net/api")
    CatFacts facts();

}

@Controller
@ResponseBody
class CatFactsController {

    private final CatFactsClient catFactsClient;

    private final AtomicInteger counter = new AtomicInteger();

    CatFactsController(CatFactsClient catFactsClient) {
        this.catFactsClient = catFactsClient;
    }

    @ConcurrencyLimit(10)
    @GetMapping("/cats/facts")
    @Retryable(includes = {IllegalStateException.class}, maxAttempts = 4)
    CatFacts facts() {

        if (this.counter.incrementAndGet() < 4) {
            IO.println("No more facts");
            throw new IllegalStateException("Something went wrong");
        }
        IO.println("all good!");

        return this.catFactsClient.facts();
    }

}

