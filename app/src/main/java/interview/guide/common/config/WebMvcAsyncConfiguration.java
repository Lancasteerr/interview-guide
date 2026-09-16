package interview.guide.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 让 Spring MVC 的 SSE/异步响应复用 Boot 管理的应用执行器。
 */
@Configuration
public class WebMvcAsyncConfiguration implements WebMvcConfigurer {

  private final AsyncTaskExecutor mvcAsyncTaskExecutor =
      new VirtualThreadTaskExecutor("mvc-async-");

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setTaskExecutor(mvcAsyncTaskExecutor);
  }
}
