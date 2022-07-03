package com.spring.batch_microservice.conf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.SimpleJobBuilder;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import com.spring.batch_microservice.custom.ColumnRangePartitioner;
import com.spring.batch_microservice.custom.CustomSkipListener;
import com.spring.batch_microservice.custom.CustomerRowMapper;
import com.spring.batch_microservice.custom.SkipItemWriter;
import com.spring.batch_microservice.exception.CustomRetryableException;
import com.spring.batch_microservice.pojo.Customer;
import com.spring.batch_microservice.service.JobLaunchingService;
import com.spring.springbatch.custom.RetryItemWriter;

@Configuration
public class SkipJobConfiguration implements ApplicationContextAware {
	
	@Autowired
	private JobBuilderFactory jobBuilderFactory;
	
	@Autowired
	private StepBuilderFactory stepBuilderFactory;

	@Autowired
	private DataSource dataSource;
	
	@Autowired
	private SkipItemWriter skipItemWriter;

	@Autowired
	private ApplicationContext applicationContext;
	
	@Autowired
	private ColumnRangePartitioner columnRangePartitioner;
	
	@Autowired
	private JobLaunchingService jobLaunchingService;
	
	@Autowired
	@StepScope
	private RetryItemWriter retryItemWriter;
	
	@Autowired
	private JdbcPagingItemReader<Customer> jdbcItemReader;
	
	@Bean
	@StepScope
	public JdbcPagingItemReader<Customer> pagingItemReader(@Value("#{stepExecutionContext['minValue']}")Long minValue,
			@Value("#{stepExecutionContext['maxValue']}")Long maxValue) {
		
		System.out.println("reading " + minValue + " to " + maxValue);
		
		JdbcPagingItemReader<Customer> reader = new JdbcPagingItemReader<>();
		
		reader.setDataSource(dataSource);
		
		// set the fetch size same as chunk size
		reader.setFetchSize(100);
		reader.setRowMapper(new CustomerRowMapper());
		
		// spring batch generates a new sql statement for each page
		MySqlPagingQueryProvider queryProvider = new MySqlPagingQueryProvider();
		queryProvider.setSelectClause("id, firstName, lastName, birthDate");
		queryProvider.setFromClause("from customer");
		queryProvider.setWhereClause("where id >= " + minValue + " and id < " + maxValue);
		
		// used to order the data .. also keeps track of last value read
		Map<String, Order> sortKeys = new HashMap<>(1);
		sortKeys.put("id", Order.ASCENDING);
		queryProvider.setSortKeys(sortKeys);
		
		reader.setQueryProvider(queryProvider);
		
		return reader;
	}
	
	// Partitioning : Master step for step1S
	@Bean
	public Step step1M() throws Exception {
		return stepBuilderFactory.get("step1M")
				.partitioner(step1S().getName(), columnRangePartitioner)
				.step(step1S())
				.gridSize(10)
				.taskExecutor(new SimpleAsyncTaskExecutor())
				.build();
	}

	@Bean
	public Step step2M() {
		
		return stepBuilderFactory.get("step2M").<Customer, Customer> chunk(100)
				.reader(jdbcItemReader)
				.writer(retryItemWriter)
				.faultTolerant()
				.retry(CustomRetryableException.class)
				.retryLimit(10)
				.build();
	}
	
	// Partitioning : Slave step for step1M
	@Bean
	public Step step1S() throws Exception{
		
		return stepBuilderFactory.get("step1S").<Customer, Customer> chunk(100)
						.reader(pagingItemReader(null, null))
						.writer(skipItemWriter)
						.faultTolerant()
						.skip(CustomRetryableException.class)
						.skipLimit(100)
						.listener(new CustomSkipListener())
						.build();
	}
	
	@Bean
	public Job skipJob() throws Exception {	
				
		SimpleJobBuilder jobBuilder = this.jobBuilderFactory.get("skipJob").start(step1M());

		List<String> stepsOrder = this.jobLaunchingService.getOrderedStepsFromDB("skipJob");

		for (String stepName : stepsOrder) {
	        jobBuilder.next(stepBuilderFactory.get(stepName).tasklet(null).build());
	    }
		
	    return jobBuilder.build();
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}
