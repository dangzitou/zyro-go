package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);
        //5.返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session 会话
     * @return 结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(code == null || !code.equals(cacheCode)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到redis中
        //7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2.将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//注意这里是BeanUtil不是BeanUtils
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", userDTO.getId().toString());
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());
        //7.3.存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        //7.4.设置token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //8.返回token
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 将今天签到状态保存到Redis中，使用bitMap数据结构
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        // 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截止到今天为止的签到记录，返回一个十进制数字
        List<Long> signCount = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (signCount == null || signCount.isEmpty()) {
            return Result.ok(0);
        }
        Long count = signCount.get(0);//这是一个十进制数字，二进制每位代表一天的签到状态，1代表签到，0代表未签到
        if (count == null) {
            return Result.ok(0);
        }
        int result = 0;
        // 循环遍历这个十进制数字，统计连续签到的天数
        while ((count & 1) == 1) {
            result++;
            count >>>= 1;
        }
        return Result.ok(result);
    }

    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
