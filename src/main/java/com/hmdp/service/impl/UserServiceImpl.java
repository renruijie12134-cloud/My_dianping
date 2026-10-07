package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1:校验手机号，不用自己写，util包中的RegexUtils中提供饿了对手机号，邮箱等的校验方法
        // 你以后写项目也要这样，把需要用到的工具提前写好放在utils包中
        boolean isPhoneValid = RegexUtils.isPhoneInvalid(phone);
        // 2:如果不符合则返回错误信息
        if (isPhoneValid) {
            return Result.fail("手机号格式无效");
        }

        // 3:如果符合则生成验证码 使用随机数生成
        // String code = String.valueOf((int) (Math.random() * 1000000));
        String code = RandomUtil.randomNumbers(6);

        // 4:保存验证码到Session
       //  session.setAttribute("code", code);  // 基于Session保存验证码，
        // 基于Redis保存验证码，设置有效期为2分钟，使用stringRedisTemplate
        // stringRedisTemplate.opsForValue().set("login:code:"+phone, code, 2, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5:发送验证码----发送验证码需要调用第三方服务，这里省略
        log.info("发送验证码({}, {})", phone, code);

        // 6:返回结果
        return Result.ok();

    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session){
        // 1:校验手机号和验证码 -- 校验手机号格式+校验验证码与Session中的验证码进行比较
        // 2:如果不一致则返回错误信息
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }
        if (cacheCode==null||!code.equals(cacheCode)) {
            // cacheCode为空也表示验证码过期了，需要重新获取验证码
            // 一般我们会给验证码设置有效时间，这里我们没有设置，所以不会过期
            return Result.fail("验证码错误或已过期");
        }
        // 3:如果一致则根据手机号查询用户
        User user=query().eq("phone", phone).one();
        // 4:如果用户不存在则应创建新用户于数据库中
        if(user==null){
            user = new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);

        log.info("登录功能生效{}", userDTO);

        // 5:如果用户存在继续
        // 6: 将用户信息保存到Session中，这样的目的是为了方便后续的用户信息展示，

        // 基于Session保存用户信息
        // session.setAttribute("user", userDTO);

        // 基于Redis保存用户信息，设置有效期为30分钟,
        // 选择hash类型，key为随机字符串，value为userDTO
        String token = UUID.randomUUID().toString();

        //这么写的目的是为了在Map转为bean时自动装换，userDTO中的id为Long类型，
        // 而Redis中存储的id为String类型，所以存的时候这把他转成字符串
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor(
                                (fieldName, fieldValue) ->
                                        fieldValue.toString()
                        )
        );

        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 返回结果
        return Result.ok(token);
    }

}
