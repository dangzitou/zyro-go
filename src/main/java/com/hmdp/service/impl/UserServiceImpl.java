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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 鏈嶅姟瀹炵幇绫?
 * </p>
 *
 * @author 铏庡摜
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码，并把验证码短期缓存到 Redis。
     */
    @Override
    public Result sendCode(String phone) {
        //1.鏍￠獙鎵嬫満鍙?
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.鐢熸垚楠岃瘉鐮?
        String code = RandomUtil.randomNumbers(6);
        //3.淇濆瓨楠岃瘉鐮佸埌redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.鍙戦€侀獙璇佺爜
        log.debug("发送短信验证码成功，验证码：{}", code);
        //5.杩斿洖ok
        return Result.ok();
    }

    /**
     * 鐧诲綍鍔熻兘
     * @param loginForm 鐧诲綍鍙傛暟锛屽寘鍚墜鏈哄彿銆侀獙璇佺爜锛涙垨鑰呮墜鏈哄彿銆佸瘑鐮?
     * @return 缁撴灉
     */
    /**
     * 手机号加验证码登录。
     * 首次登录会自动创建用户，并把登录态写入 Redis。
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        //1.鏍￠獙鎵嬫満鍙?
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.浠巖edis涓幏鍙栭獙璇佺爜骞舵牎楠?
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(code == null || !code.equals(cacheCode)){
            //3.涓嶄竴鑷达紝鎶ラ敊
            return Result.fail("验证码错误");
        }

        //4.涓€鑷达紝鏍规嵁鎵嬫満鍙锋煡璇㈢敤鎴?
        User user = query().eq("phone", phone).one();
        //5.鍒ゆ柇鐢ㄦ埛鏄惁瀛樺湪
        if(user == null){
            //6.涓嶅瓨鍦紝鍒涘缓鏂扮敤鎴峰苟淇濆瓨
            user = createUserWithPhone(phone);
        }

        //7.淇濆瓨鐢ㄦ埛淇℃伅鍒皉edis涓?
        //7.1.闅忔満鐢熸垚token锛屼綔涓虹櫥褰曚护鐗?
        String token = UUID.randomUUID().toString(true);
        //7.2.灏哢ser瀵硅薄杞负Hash瀛樺偍
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//娉ㄦ剰杩欓噷鏄疊eanUtil涓嶆槸BeanUtils
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", userDTO.getId().toString());
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());
        //7.3.瀛樺偍
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        //7.4.璁剧疆token鏈夋晥鏈?
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //8.杩斿洖token
        return Result.ok(token);
    }

    /**
     * 退出登录。
     * 当前实现主要是清理线程上下文里的用户信息。
     */
    @Override
    public Result logout() {
        UserHolder.removeUser();
        return Result.ok();
    }

    /**
     * 用户签到，把当月签到状态写入 Redis Bitmap。
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        // 鑾峰彇褰撳墠鏃ユ湡
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 鑾峰彇浠婂ぉ鏄湰鏈堢殑绗嚑澶?
        int dayOfMonth = now.getDayOfMonth();
        // 灏嗕粖澶╃鍒扮姸鎬佷繚瀛樺埌Redis涓紝浣跨敤bitMap鏁版嵁缁撴瀯
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计当前用户的连续签到天数。
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        // 鑾峰彇褰撳墠鏃ユ湡
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + ":" + keySuffix;
        // 鑾峰彇浠婂ぉ鏄湰鏈堢殑绗嚑澶?
        int dayOfMonth = now.getDayOfMonth();
        // 鑾峰彇鏈湀鎴鍒颁粖澶╀负姝㈢殑绛惧埌璁板綍锛岃繑鍥炰竴涓崄杩涘埗鏁板瓧
        List<Long> signCount = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (signCount == null || signCount.isEmpty()) {
            return Result.ok(0);
        }
        Long count = signCount.get(0);//杩欐槸涓€涓崄杩涘埗鏁板瓧锛屼簩杩涘埗姣忎綅浠ｈ〃涓€澶╃殑绛惧埌鐘舵€侊紝1浠ｈ〃绛惧埌锛?浠ｈ〃鏈鍒?
        if (count == null) {
            return Result.ok(0);
        }
        int result = 0;
        // 寰幆閬嶅巻杩欎釜鍗佽繘鍒舵暟瀛楋紝缁熻杩炵画绛惧埌鐨勫ぉ鏁?
        while ((count & 1) == 1) {
            result++;
            count >>>= 1;
        }
        return Result.ok(result);
    }

    /**
     * 首次登录时创建新用户。
     */
    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        save(user);
        return user;
    }
}

