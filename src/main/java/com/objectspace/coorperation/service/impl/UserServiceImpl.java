package com.objectspace.coorperation.service.impl;

import com.google.code.kaptcha.Constants;
import com.objectspace.coorperation.config.ConstantValue;
import com.objectspace.coorperation.dao.UserDao;
import com.objectspace.coorperation.dto.UserExecution;
import com.objectspace.coorperation.entity.User;
import com.objectspace.coorperation.enums.UserStateEnum;
import com.objectspace.coorperation.service.UserService;
import com.objectspace.coorperation.util.ImageUtil;
import com.objectspace.coorperation.util.RedisUtil;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.DisabledAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.SimpleHash;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.multipart.commons.CommonsMultipartFile;

import java.io.*;
import java.util.Date;
import java.util.Properties;

/**
* @Description: 用户业务逻辑实现类
* @Author: NoCortY
* @Date: 2019/10/4
*/
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    UserDao userDao;
    @Autowired
    RedisUtil redisUtil;
    Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);
    /**
     * @Description:  通过用户名获取用户信息
     * @Param: [user]
     * @return: com.objectspace.coorperation.dto.UserExecution
     * @Author: NoCortY
     * @Date: 2019/10/4
     */
    @Override
    public UserExecution getUserByUserName(User user) {
        UserExecution userExecution = null;
        User userInDB = null;
        if (user == null || "".equals(user.getUserName())||user.getUserName() == null) {
            userExecution = new UserExecution(UserStateEnum.ISUSERNULL);
            return userExecution;
        }else{
            try{
                userInDB = userDao.queryUserByUserName(user);
            }catch(Exception e){
                userExecution = new UserExecution(UserStateEnum.SYSTEMERROR);
                logger.error("查询用户异常 by username");
                logger.error("异常信息:"+e.getMessage());
                return userExecution;
            }
            userExecution = new UserExecution(UserStateEnum.QUERYUSERINFOSUCCESS,userInDB);
            logger.info("查询用户信息成功");
            logger.info("用户信息"+userInDB);
        }
        return userExecution;
    }

    /**
     * @Description:  添加一个用户
     * @Param: [user, captchaCode, userProfile]
     * @return: com.objectspace.coorperation.dto.UserExecution
     * @Author: NoCortY
     * @Date: 2019/10/4
     */
    @Override
    @Transactional
    public UserExecution addUser(User user,String captchaCode, CommonsMultipartFile userProfile) {
        // TODO 注册用户
        UserExecution userExecution = null;
        if (user == null || "".equals(user.getUserName())) {
            userExecution = new UserExecution(UserStateEnum.ISUSERNULL);
            return userExecution;
        }
        String code = redisUtil.get("captcha:"+user.getUserEmail());
        if("failure".equals(code)){
            userExecution = new UserExecution(UserStateEnum.SYSTEMERROR);
            logger.error("redis服务器故障，可能已经关闭");
            return userExecution;
        }
        if(!captchaCode.equals(code)) {
            userExecution = new UserExecution(UserStateEnum.VIRIFYCODEERROR);
            return userExecution;
        }
        //账号默认正常
        user.setUserStatus(ConstantValue.NORMAL_ACCOUNT);
        //以普通方式注册
        user.setUserType(ConstantValue.SIMPLE_USER);
        // 获取盐
        String salt = new SecureRandomNumberGenerator().nextBytes().toString();
        // 获取密码明文
        String password = user.getPassword();
        // 加密次数
        int times = ConstantValue.PASSWORD_ENCRYPTION_TIME;
        // 加密方式
        String algorithmName = ConstantValue.PASSWORD_ENCRYPTION_TYPE;
        // 加密
        String encodedPassword = new SimpleHash(algorithmName, password, salt, times).toString();
        user.setPassword(encodedPassword);
        user.setSalt(salt);
        //记录有效行数
        int effectiveCount = 0;
        try{
            effectiveCount = userDao.insertUser(user);
        }catch(Exception e) {
            e.printStackTrace();
            logger.error("UserAccount重复");
            logger.error("错误信息:用户在输入一个库中已存在的UserAccount");
            userExecution = new UserExecution(UserStateEnum.USERACCOUNTREPEAT);
            //回滚事务
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return userExecution;
        }
        //如果存在用户头像，则创建头像文件
        if (userProfile != null&&effectiveCount >0) {
            try {
                String relativeAddr = ImageUtil.generateUserProfile(userProfile,user.getUserId()+"/");
                user.setProfileImg(relativeAddr);
                System.out.println(user.getUserId());
                effectiveCount = userDao.updateUserByUserId(user);
            }catch (Exception e){
                //回滚事务
                logger.error("新增用户头像出现异常，事务回滚。");
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                userExecution = new UserExecution(UserStateEnum.SYSTEMERROR);
                return userExecution;
            }
            /*2019年11月7日  修改为使用Thumbnail存储头像，无需手动转换流
            //配置文件proerties
            Properties pro = getConfigProperties();
            //头像文件目录
            String profileDirPath = pro.getProperty("profilePath")+"\\"+user.getUserName();
            //一路创建头像目录文件夹
            createFolders(profileDirPath);
            FileOutputStream fileOutStream = null;
            InputStream inStream = null;
            String profilePath = null;
            try {
                *//*在硬盘中存储用户头像*//*
                //文件头像 = “文件基础路径\用户id\用户头像名”
                profilePath = profileDirPath+"\\"+userProfile.getOriginalFilename();
                fileOutStream = new FileOutputStream(profilePath);
                inStream = userProfile.getInputStream();
                //流缓冲区
                byte[] buffer = new byte[1024];
                int len;
                while((len=inStream.read(buffer))!=-1) {
                    fileOutStream.write(buffer,0,len);
                }
                user.setProfileImg("/"+user.getUserName()+"/"+userProfile.getOriginalFilename());
            } catch (FileNotFoundException e) {
                logger.error("文件未找到");
                logger.error("异常信息："+e.getMessage());
            } catch (IOException e) {
                logger.error("文件io失败");
                logger.error("异常信息："+e.getMessage());
            }finally {
                try {
                    fileOutStream.close();
                } catch (IOException e) {
                    logger.error("流关闭失败");
                    logger.error("异常信息："+e.getMessage());
                }
            }*/
        }
        if(effectiveCount<=0) {
            userExecution = new UserExecution(UserStateEnum.SYSTEMERROR);
            logger.error("注册失败，事务回滚。");
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }else {
            userExecution = new UserExecution(UserStateEnum.REGISTERSUCCESS,user);
        }
        return userExecution;
    }

    /**
     * @Description: 用户登录
     * @Param: [user, captcha]
     * @return: com.objectspace.coorperation.dto.UserExecution
     * @Author: NoCortY
     * @Date: 2019/10/4
     */
    @Override
    public UserExecution userLogin(User user,String captcha) {
        // TODO Auto-generated method stub
        UserExecution userExecution = null;
        //获取验证码
        Session session = SecurityUtils.getSubject().getSession();
        String rightCaptcha = (String) session.getAttribute(Constants.KAPTCHA_SESSION_KEY);
        if(captcha==null||"".equals(captcha)||!rightCaptcha.equals(captcha)){
            userExecution = new UserExecution(UserStateEnum.VIRIFYCODEERROR);
            return userExecution;
        }
        if (user.getUserName() == null || "".equals(user.getUserName())) {
            userExecution = new UserExecution(UserStateEnum.ISUSERNULL);
            return userExecution;
        }
        Subject subject = SecurityUtils.getSubject();
        //获取用户账户密码令牌
        UsernamePasswordToken token = new UsernamePasswordToken(user.getUserName(), user.getPassword());
        try {
            //验证令牌
            subject.login(token);
        } catch (DisabledAccountException e) {
            logger.info("用户:"+user.getUserName()+"已是封禁用户，但在尝试登录");
            userExecution = new UserExecution(UserStateEnum.USERISBINDED);
            return userExecution;
        } catch (AuthenticationException e) {
            logger.info("用户:"+user.getUserName()+"认证失败");
            userExecution = new UserExecution(UserStateEnum.LOGINFAILURE);
            return userExecution;
        }
        userExecution = new UserExecution(UserStateEnum.LOGINSUCCESS,user);
        //如果登录成功了，那么直接从数据库中获取用户id，将用户id放入session中
        Integer userId = userDao.queryUserByUserName(user).getUserId();
        session.setAttribute("userId",userId);
        return userExecution;
    }
    /**
     * @Description: 获取config.properties文件,2019/11/9日，废弃该方法，引入PathUtil和Thumbnail
     * @Param: []
     * @return: java.util.Properties
     * @Author: NoCortY
     * @Date: 2019/10/4
     */
    @Deprecated
    public Properties getConfigProperties() {
        Properties pro = new Properties();
        InputStream inStream = this.getClass().getResourceAsStream("/config/config.properties");
        try {
            pro.load(inStream);
        } catch (IOException e) {
            logger.error("Properties文件加载失败");
            logger.error("异常信息:"+e.getMessage());
        }
        return pro;
    }
    /**
     * @Description:  如果文件不存在则直接从根路径一路创建,2019/11/9日，废弃该方法，引入PathUtil和Thumbnail
     * @Param: [path]
     * @return: void
     * @Author: NoCortY
     * @Date: 2019/10/4
     */
    @Deprecated
    public void createFolders(String path) {
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
    }
}