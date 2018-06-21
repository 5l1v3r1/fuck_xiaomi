package com.fuck.xiaomi.service;


import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fuck.xiaomi.annotation.Async;
import com.fuck.xiaomi.annotation.Resource;
import com.fuck.xiaomi.annotation.Retry2;
import com.fuck.xiaomi.annotation.Service;
import com.fuck.xiaomi.annotation.Stop;
import com.fuck.xiaomi.annotation.Timing;
import com.fuck.xiaomi.enums.TimingType;
import com.fuck.xiaomi.manage.FilePathManage;
import com.fuck.xiaomi.manage.MyThreadPool;
import com.fuck.xiaomi.manage.StatusManage;
import com.fuck.xiaomi.manage.Config;
import com.fuck.xiaomi.pojo.Cookie;
import com.fuck.xiaomi.pojo.User;
import com.fuck.xiaomi.utils.FileUtil;

/**
 * 小米抢购服务
 * @author liwei
 * @date: 2018年6月11日 下午1:48:31
 *
 */
@Service
public class XiaoMiService {
	
	private static  Logger logger = LoggerFactory.getLogger(XiaoMiService.class);
	
	@Resource
	private HttpService httpService;
	
	private ScheduledFuture<?> buy;
	
	private ScheduledFuture<?> stop;
	
	
	public boolean islogin(){
		if(!FileUtil.isFile(FilePathManage.userConfig)){
			return false;
		}
		String miString = FileUtil.readFileToString(FilePathManage.userConfig);
		if(miString==null||miString.length()==0){
			return false;
		}
		User oldUser = JSON.parseObject(miString, User.class);
		if(oldUser==null){
			return false;
		}
		if(!oldUser.equals(Config.user)){
			return false;
		}
		if(oldUser.getCookies()==null||oldUser.getCookies().size()==0){
			return false;
		}
		boolean islogin = false; 
		for(Cookie cookie : oldUser.getCookies()){
			if("userId".equals(cookie.getName())){
				islogin = true;
			}
		}
		if(islogin){
			Config.user.setCookies(oldUser.getCookies());
			return true;
		}
		return false;

	}
	
	/**
	 * 保持登录状态
	 */
	@Async
	public void keeplogin() {
		if(!islogin()){
			StatusManage.isLogin = false;
			login();
			StatusManage.isLogin = true;
		}else{
			logger.info("用户:{} 已登录。",Config.user.getUserName());
			StatusManage.isLogin = true;
		}
	}
	
	@Retry2(success = "ok")
	public String login() {
		long start = System.currentTimeMillis();
		FileUtil.writeToFile(JSON.toJSONString(Config.user), FilePathManage.userConfig);
		String result = httpService.execute(FilePathManage.loginJs);
		if(result.length()==0||result.equals("cache")){
			logger.error("用户:{} 登录失败,时间:{}ms,正准备重试。。。建议清空缓存。",Config.user.getUserName(),System.currentTimeMillis()-start);
			return "fail";
		}else if(result.equals("pwd")){
			stop("用户名或密码错误！");
			return "ok";
		}else{
			List<Cookie> cookies = JSON.parseArray(result, Cookie.class);
			Config.user.setCookies(cookies);
			FileUtil.writeToFile(JSON.toJSONString(Config.user), FilePathManage.userConfig);
			logger.info("用户:{} 登录成功,时间:{}ms",Config.user.getUserName(),System.currentTimeMillis()-start);
			return "ok";
		}
		
	}
	
	/**
	 * 每秒开一个线程，共开8个线程,去获取购买url
	 */
	@Async(value = 8, interval = 1000)
	public void getBuyUrl(){
		buyUrl();
	}
	
	@Retry2(success = "ok",interval = 500)
	public String  buyUrl() {
		if(StatusManage.isBuyUrl){
			return "ok";
		}
		if(StatusManage.isLogin){
			String result = httpService.execute(FilePathManage.buyGoodsJs);
			if(result.startsWith("[")&&result.endsWith("]")&&result.length()>10){
				List<String> buyUrl = JSON.parseArray(result, String.class);
				Config.goodsInfo.setBuyUrls(buyUrl);
				StatusManage.isBuyUrl = true;
				logger.info("购买链接:{},开始抢购！",Config.goodsInfo.getBuyUrls());
				submitOrder();
				return "ok";
			}
		}
		return "fail";
		
	}

	@Async
	public void submitOrder() {
		String result = httpService.execute(FilePathManage.submitOrderJs);
		logger.info("购物车:{}",result);
	}

	/**
	 * httpClient执行购买
	 * @param buyUrl
	 * @param cookies
	 */
	@Timing(initialDelay = 0, period = 400, type = TimingType.FIXED_RATE, unit = TimeUnit.MILLISECONDS)
	public void buyGoodsTask() {
		if(StatusManage.isLogin&&StatusManage.isBuyUrl){
			buy(Config.goodsInfo.getBuyUrls(),Config.user.getCookies());
		}
	}
	
	@Async(30)
	public void buy(List<String> buyUrl, List<Cookie> cookies){
		for(String url:buyUrl){
			long start = System.currentTimeMillis();
			String re = httpService.getByCookies(url, cookies);
			if(re!=null){
				if(isBuySuccess(re)){
					submitOrder();
					stop("恭喜！抢购成功,赶紧去购物车付款吧!");
					return;
				}
				logger.info("排队中({}),看人品咯！{}ms,{}",Config.submitCount.addAndGet(1),System.currentTimeMillis()-start,url);
			}
			
		}
	}
	
	public void start(){
		//购买
		buy = MyThreadPool.schedule(()->{
			logger.info("获取购买链接中。。。");
			getBuyUrl();
			buyGoodsTask();
			
		}, Config.customRule.getBuyTime(), TimeUnit.MILLISECONDS);
		//抢购时间截止
		stop = MyThreadPool.schedule(()->{
			stop("抢购时间截止，停止抢购");
		}, Config.customRule.getEndTime(), TimeUnit.MILLISECONDS);

	}
	@Stop(methods = { "buyGoodsTask"})
	public void stop(String msg) {
		
		StatusManage.endMsg = msg;
		logger.info(msg);
		
		if(buy!=null){
			buy.cancel(false);//停止 购买定时器
		}
		
		if(stop!=null){
			stop.cancel(false);//停止 截止时间的定时器
		}
		
		StatusManage.isBuyUrl = true;//停止buyUrl

		StatusManage.isEnd = true;
	}
	
	//判断是否抢购成功 
		//jQuery111302798960934517918_1528978041106({"code":1,"message":"2173300005_0_buy","msg":"2173300005_0_buy"});
		public boolean isBuySuccess(String re) {
			try{
				String substring = re.substring(re.indexOf("(")+1,re.lastIndexOf(")"));
				JSONObject parseObject = JSON.parseObject(substring);
				Integer code = parseObject.getInteger("code");
				return code==1;
			}catch(Exception e){
				logger.error("parseBuyResult err:{}",re);
				return false;
			}
		}
	
}