package com.example.crmshell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jline.utils.AttributedString;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.Nullable;
import org.springframework.shell.Availability;
import org.springframework.shell.CompletionContext;
import org.springframework.shell.CompletionProposal;
import org.springframework.shell.jline.PromptProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.shell.standard.ValueProvider;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.PrintStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootApplication
public class CrmShellApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrmShellApplication.class, args);
    }
}

@Data
@NoArgsConstructor
@AllArgsConstructor
class Person {

    private Long id;
    private String name;
}

@Service
class ConsoleService {

    private final static String ANSI_YELLOW = "\u001B[33m";
    private final static String ANSI_RESET = "\u001B[0m";

    private final PrintStream out = System.out;

    public void write(String msg, String... args) {
        this.out.print("> ");
        this.out.print(ANSI_YELLOW);
        this.out.printf(msg, (Object[]) args);
        this.out.print(ANSI_RESET);
        this.out.println();
    }
}

@Service
class CrmService implements InitializingBean {

    private final Map<Long, Person> people = new ConcurrentHashMap<>();

    private final AtomicBoolean connected = new AtomicBoolean();

    boolean isConnected() {
        return this.connected.get();
    }

    void connect(String usr, String pw) {
        this.connected.set(true);
    }

    void disconnect() {
        this.connected.set(false);
    }

    Person findById(Long id) {
        return this.people.get(id);
    }

    Collection<Person> findByName(String name) {
        return this.people.values()
                .stream()
                .filter(p -> p.getName().toLowerCase().contains(name.toLowerCase()))
                .collect(Collectors.toList());
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        AtomicLong ids = new AtomicLong();
        Map<Long, Person> personMap = Stream.of("Brian Dussault", "Brian Clozel", "Stephane Maldini", "Stephane Nicoll",
                "James Watters", "James Bayer", "Cornelia Davis", "Madhura Bhave", "Eric Bottard")
                .map(name -> new Person(ids.incrementAndGet(), name))
                .collect(Collectors.toMap(Person::getId, p -> p));
        this.people.putAll(personMap);
    }
}


@Component
class PersonConverter implements Converter<String, Person> {

    private final CrmService crm;

    private final Pattern pattern = Pattern.compile("\\(#(\\d+)\\).*");

    PersonConverter(CrmService crm) {
        this.crm = crm;
    }
    // (#42) foo bar

    @Nullable
    @Override
    public Person convert(String source) {

        Matcher matcher = this.pattern.matcher(source);
        if (matcher.find()) {
            String group = matcher.group(1);
            if (StringUtils.hasText(group)) {
                Long id = Long.parseLong(group);
                return this.crm.findById(id);
            }
        }

        return null;
    }
}

@Component
class PersonValueProvider implements ValueProvider {

    private final CrmService crm;

    PersonValueProvider(CrmService crm) {
        this.crm = crm;
    }

    @Override
    public boolean supports(MethodParameter parameter, CompletionContext completionContext) {
        return parameter.getParameterType().isAssignableFrom(Person.class);
    }

    @Override
    public List<CompletionProposal> complete(MethodParameter parameter,
                                             CompletionContext completionContext,
                                             String[] hints) {
        String currentInput = completionContext.currentWordUpToCursor();
        return this.crm
                .findByName(currentInput)
                .stream()
                .map(p -> String.format("(#%s) %s", p.getId(), p.getName()))
                .map(CompletionProposal::new)
                .collect(Collectors.toList());
    }
}

@ShellComponent
class PeopleCommands {

    private final ConsoleService console;

    PeopleCommands(ConsoleService console) {
        this.console = console;
    }

    @ShellMethod("interact with the directory")
    public void directory(
            @ShellOption(valueProvider = PersonValueProvider.class) Person person) {
        this.console.write("working with %s.", person.getName());
    }
}

@Component
class ConnectedPromptProvider implements PromptProvider {

    private final CrmService crm;

    ConnectedPromptProvider(CrmService crm) {
        this.crm = crm;
    }

    @Override
    public AttributedString getPrompt() {
        String msg = String.format("spring CRM (%s)> ", this.crm.isConnected() ? "connected" : "disconnected");
        return new AttributedString(msg);
    }
}

@ShellComponent
class ConnectionCommands {

    private final ConsoleService console;
    private final CrmService crm;

    ConnectionCommands(ConsoleService console, CrmService crm) {
        this.console = console;
        this.crm = crm;
    }

    @ShellMethod("connect to the CRM")
    public void connect(String username, String password) {
        this.crm.connect(username, password);
        this.console.write("connected %s.", username);
    }


    Availability connectAvailability() {
        return !this.crm.isConnected() ?
                Availability.available() : Availability.unavailable("you're already connected");
    }

    @ShellMethod("disconnect from the CRM")
    public void disconnect() {
        this.crm.disconnect();
    }

    Availability disconnectAvailability() {
        return this.crm.isConnected() ?
                Availability.available() : Availability.unavailable("you're not connected");
    }

}
