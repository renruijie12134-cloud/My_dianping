package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            // 没有Token，说这个用户没有登录凭证，不能让他进，应该拦截
            // 先让他过，因为它没有向 TreadLocal 中写数据，所以在第二个拦截器中会被拦截
            return true;
        }

        log.info("拦截器1生效,且token存在");

        // 2.基于token获取Redis中的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                            .entries(RedisConstants.LOGIN_USER_KEY + token);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            // useMap为空，说明他长时间未登录，需要重新登录,故而要拦截
            // 先让他过，因为它没有向 TreadLocal 中写数据，所以在第二个拦截器中会被拦截
            return true;
        }
        // 5.存在，保存用户信息到ThreadLocal
        //这么写的目的是为了在Map转为bean时自动装换，userDTO中的id为Long类型，而Redis中存储的id为String类型，所以存的时候这把他转成字符串
        //现在要取出，所以就要把String转为Long
        // BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false); 自动实现上述过程
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        UserHolder.saveUser(userDTO );
        log.info("成功保存用户信息到ThreadLocal{}", userDTO);

        // 6.刷新Redis缓存的有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);


        // 7.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 一定移除用户信息呀，要不然等这个线程回到线程池，随后又被其他请求复用，就会导致用户信息混乱
        UserHolder.removeUser();
    }
}
