package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.annotations.Update;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;


    /**
     * 发布保存探店博文并推送给它的粉丝
     * @param blog
     * @return
     */
    @Override
    public  Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess=save(blog);
        // 3.如果数据库保存成功，就把博文推送给关注我的粉丝
        if(isSuccess){
            // 先获取关注我的粉丝 select userid from follow where followee_id = ?
            List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
            // 给每一个粉丝推送
            for (Follow follow : follows){
                // 获取粉丝ID
                Long userId = follow.getUserId();
                // 设置key
                String key = "feed:" + userId;
                // 推送
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),System.currentTimeMillis());
            }
        }else{
            return Result.fail("保存失败");
        }
        // 3.返回id
        return Result.ok(blog.getId());

    }

    /**
     * 查询热门博文
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(int current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
                }
        );
        return Result.ok(records);
    }


    /**
     * 查询博文详情
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        // 2.查询有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        if(userId == null){
            // 在点赞功能中，如果用户未登录，则不需要查询用户自己是否点赞
            return;
        }
        // 2.判断当前用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    /**
     * 点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.从TreadLocal中获取当前用户ID
        Long userId = UserHolder.getUser().getId();
        // 2.利用Redis的Set数据结构判断当前用户是否已经点赞
        // 2.1 先生成key
        String key = "blog:liked:"+id;
        // 2.2 利用Redis的ZSet数据结构判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3. 判断是否已经点赞
        // 3.1 没点赞
        if(score == null){
            // 3.1.1 可以点赞 先写数据库，数据库写入成功再更新Redis
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                // 为了实现排行榜的功能，将数据放入ZSet,使用时间戳作为score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3.2 已点赞
            // 3.2.1 取消点赞 先写数据库，数据库写入成功再更新Redis
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 点赞人排行榜top5
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1.先从Redis的ZSet中获取对该博客点赞的用户ID
        String key = "blog:liked:" + id;
        // 1.1 获取ZSet中的top5成员
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 1.2 解析出用户ID，将top5中的用户ID转换为Long类型
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", userIds);
        // 1.3 根据用户ID查询用户信息
        List<User> users = userService
                .query()
                .in("id", userIds)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 1.4 将users列表转为userDTO列表
        List<UserDTO> userdtos = users.stream().map(user-> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userdtos);
    }


}
