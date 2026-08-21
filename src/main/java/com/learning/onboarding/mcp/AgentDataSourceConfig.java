package com.learning.onboarding.mcp;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two connections to the same database, with very different rights.
 *
 * <pre>
 *   dataSource       owner        reads and writes everything    the application
 *   agentDataSource  review_agent SELECT on 4 reference tables   the agents
 * </pre>
 *
 * <h2>Why a separate connection rather than careful code</h2>
 * Because careful code is a promise and a missing GRANT is a fact. Every
 * prompt-level control in this project is advisory: an injection scanner can be
 * evaded, spotlighting can be argued around, an instruction can be overridden by
 * a cleverer instruction. This one cannot be argued with. If the agent layer is
 * fully compromised and emits {@code DELETE FROM review_finding}, Postgres
 * refuses it.
 *
 * <p>It also enforces reviewer independence at the database level: the agent
 * role has no access to {@code review_finding}, so a compliance reviewer
 * physically cannot read what the quality reviewer concluded. That is the
 * assumption the whole multi-agent argument rests on, and it is worth more than
 * a comment asking people not to.
 *
 * <h2>Why the primary DataSource is declared here too</h2>
 * Spring Boot's {@code DataSourceAutoConfiguration} is conditional on there
 * being no {@code DataSource} bean at all. Declaring the agent one silently
 * switches the application's own connection off - and the symptom is Flyway
 * picking the read-only agent connection and failing to authenticate, which
 * points nowhere near the cause.
 */
@Configuration
public class AgentDataSourceConfig {

    public static final String AGENT_JDBC = "agentJdbcTemplate";

    /**
     * The application's connection. Explicit because declaring any DataSource
     * bean turns off Boot's auto-configured one.
     */
    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
    }

    /**
     * The agents' connection. Read-only at the pool as well as at the server:
     * an accidental write then fails fast in the driver with a clear message
     * rather than as a permission error from Postgres.
     */
    @Bean(defaultCandidate = false)
    @Qualifier("agentDataSource")
    public DataSource agentDataSource(
            @Value("${onboarding.agent-datasource.url:${spring.datasource.url}}") String url,
            @Value("${onboarding.agent-datasource.username:review_agent}") String username,
            @Value("${onboarding.agent-datasource.password:${AGENT_DB_PASSWORD:agent-local-dev-only}}")
            String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setReadOnly(true);
        ds.setPoolName("agent-readonly");
        // Small: tools are short lookups, and a large pool of read-only
        // connections is only a way to hide a runaway agent loop.
        ds.setMaximumPoolSize(5);
        return ds;
    }

    /**
     * {@code defaultCandidate = false} so this can only be injected with an
     * explicit qualifier.
     *
     * <p>Without it, any {@code @Autowired JdbcTemplate} might silently receive
     * the read-only connection, and the symptom is bizarre: queries succeed but
     * return almost nothing, because Postgres hides tables the current role has
     * no rights on. A test asking for the table list got four instead of eleven
     * and looked like a broken migration.
     */
    @Bean(name = AGENT_JDBC, defaultCandidate = false)
    @Qualifier(AGENT_JDBC)
    public JdbcTemplate agentJdbcTemplate(@Qualifier("agentDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
