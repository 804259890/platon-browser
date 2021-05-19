package com.platon.browser.bean;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @Auther: Chendongming
 * @Date: 2019/8/21 15:09
 * @Description: 年化率信息bean
 */
@Data
public class AnnualizedRateInfo {
    private List<PeriodValueElement> stakeProfit = new ArrayList<>();
    private List<PeriodValueElement> stakeCost = new ArrayList<>();
    private List<PeriodValueElement> delegateProfit = new ArrayList<>();
    private List<PeriodValueElement> delegateCost = new ArrayList<>();
    private List<SlashInfo> slash = new ArrayList<>();
    public String toJSONString(){return JSON.toJSONString(this);}
}
