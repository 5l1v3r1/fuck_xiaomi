package com.fuck.xiaomi.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.fuck.xiaomi.annotation.Controller;
import com.fuck.xiaomi.annotation.Resource;
import com.fuck.xiaomi.annotation.Singleton;
import com.fuck.xiaomi.db.LogStorage;
import com.fuck.xiaomi.manage.FilePathManage;
import com.fuck.xiaomi.manage.StatusManage;
import com.fuck.xiaomi.manage.Config;
import com.fuck.xiaomi.service.LogService;
import com.fuck.xiaomi.service.XiaoMiService;
import com.fuck.xiaomi.utils.FileUtil;

/**
 * 抢购小米
 * @author liwei
 * @date: 2018年6月11日 上午11:54:32
 *
 */
@Controller
public class XiaoMiController {
	
	private static  Logger logger = LoggerFactory.getLogger(XiaoMiController.class);
	
	@Resource
	private XiaoMiService xiaomiService;
	
	
	@Resource
	private LogService logService;
	
	
	public void start(){
		logger.info("param:{},{},{}",Config.user,Config.goodsInfo,Config.customRule);
		
		StatusManage.isLogin = false;
		StatusManage.isBuyUrl = false;
		FileUtil.writeToFile(JSON.toJSONString(Config.goodsInfo), FilePathManage.goodsInfoConfig);
		xiaomiService.keeplogin();
		xiaomiService.start();
		
		
	}
	
	@Singleton
	public void init() {
		logService.readLogs();
	}

	public String loadLog() {
		return LogStorage.getLog();
	}
}