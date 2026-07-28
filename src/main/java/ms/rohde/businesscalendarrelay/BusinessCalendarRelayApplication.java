package ms.rohde.businesscalendarrelay;

import ms.rohde.hexagonalarch.spring.ArchComponentScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Configuration
@EnableScheduling
@ArchComponentScan("ms.rohde.businesscalendarrelay")
public class BusinessCalendarRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessCalendarRelayApplication.class, args);
    }
}
