package top.aiolife.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(QueryProperties.class)
public class QueryApplication {
    public static void main(String[] args) { SpringApplication.run(QueryApplication.class, args); }
}
