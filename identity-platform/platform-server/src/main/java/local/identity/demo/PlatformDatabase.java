package local.identity.demo;

import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class PlatformDatabase {
    @Bean(initMethod = "migrate")
    Flyway platformMigrations(DataSource source) {
        return Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("0")
                .locations("classpath:db/migration").load();
    }
    @Bean Clock platformClock() { return Clock.systemUTC(); }
}
