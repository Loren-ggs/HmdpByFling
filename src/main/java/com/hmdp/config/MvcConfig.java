package com.hmdp.config;

import com.hmdp.utils.LoginIntercepter;
import com.hmdp.utils.RefreshIntercepter;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        registry.addInterceptor(new RefreshIntercepter(stringRedisTemplate))
                .excludePathPatterns(
                        "/user/code",
                        "/shop/**",
                        "/user/login"
                );      //刷新拦截器在全局生效


        registry.addInterceptor(new LoginIntercepter())//登录状态拦截器
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",        // 修改为 /shop/** 以排除所有shop相关请求
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                );

    }
}
