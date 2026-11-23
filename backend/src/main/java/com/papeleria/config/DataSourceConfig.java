package com.papeleria.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DataSourceConfig {

  private static final String WRITE_PARAMS =
      "?transaction_mode=IMMEDIATE&journal_mode=WAL&synchronous=FULL&foreign_keys=true&busy_timeout=5000"
          + "&cache_size=-65536&temp_store=MEMORY";
  private static final String READ_PARAMS =
      "?journal_mode=WAL&foreign_keys=true&busy_timeout=5000";

  @Bean
  @Primary
  public DataSource writeDataSource(AppProps p) throws IOException {
    Path db = Path.of(p.dbPath()).toAbsolutePath();
    Files.createDirectories(db.getParent());
    var c = new HikariConfig();
    c.setPoolName("write");
    c.setJdbcUrl("jdbc:sqlite:" + db + WRITE_PARAMS);
    c.setMaximumPoolSize(1);
    return new HikariDataSource(c);
  }

  @Bean
  public DataSource readDataSource(AppProps p) {
    var c = new HikariConfig();
    c.setPoolName("read");
    c.setJdbcUrl("jdbc:sqlite:" + Path.of(p.dbPath()).toAbsolutePath() + READ_PARAMS);
    c.setMaximumPoolSize(4);
    c.setConnectionInitSql("PRAGMA query_only=ON");
    c.setInitializationFailTimeout(-1);
    return new HikariDataSource(c);
  }

  @Bean
  @Primary
  public JdbcTemplate jdbc(@Qualifier("writeDataSource") DataSource ds) {
    return new JdbcTemplate(ds);
  }

  @Bean
  public JdbcTemplate readJdbc(@Qualifier("readDataSource") DataSource ds) {
    return new JdbcTemplate(ds);
  }

  @Bean
  @Primary
  public PlatformTransactionManager transactionManager(@Qualifier("writeDataSource") DataSource ds) {
    return new DataSourceTransactionManager(ds);
  }
}
