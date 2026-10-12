package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;


    /**
     * 关注或者取关
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 从ThreadLocal中获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        // 判断是关注还是取关
        if (isFollow) {
            // 关注 插入数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            Boolean isSuccess = save(follow);
            // 如果数据库操作成功，就把这组数据放入Redis的Set中，表示当前用户关注了followUserId
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关 删除数据
            Boolean isSuccess=remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            // 如果数据库操作成功，就从Redis的Set中删除这组数据，表示当前用户取关了followUserId
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 是否关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 判断当前用户是否关注了followUserid用户
        Long userId = UserHolder.getUser().getId();
        int result = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(result > 0);
    }

    /**
     * 获取共同关注
     */
    @Override
    public Result commonFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        // 获取当前用户和followUserId的共同关注的用户iD
        String key2 = "follow:" + followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect == null || intersect.isEmpty()) {
            // 没有共同关注
            return Result.ok();
        }
        List<Long> userList = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 根据用户ID查询用户信息,并强制复制到userDTO对象中
        List<UserDTO> users = userService.listByIds(userList).stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

}
