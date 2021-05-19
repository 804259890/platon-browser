package com.platon.browser.bean;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @Auther: dongqile
 * @Date: 2019/12/5 15:09
 * @Description: 年化率信息bean单元测试
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AnnualizedRateInfoTest {

    @Test
    public void test(){
        AnnualizedRateInfo ari = new AnnualizedRateInfo();
        PeriodValueElement profit = new PeriodValueElement();
        profit.setPeriod(100L);
        profit.setValue(BigDecimal.TEN);
        ari.getStakeProfit().add(profit);
        assertEquals(profit.getPeriod().longValue(),100L);

        PeriodValueElement cost = new PeriodValueElement();
        cost.setPeriod(120L);
        cost.setValue(BigDecimal.TEN);
        List<PeriodValueElement> costList = new ArrayList <>();
        ari.getStakeCost().add(cost);
        SlashInfo slash = new SlashInfo();
        slash.setSlashTime(new Date());
        slash.setBlockCount(BigInteger.ONE);
        slash.setBlockNumber(BigInteger.ONE);
        slash.setKickOut(false);
        slash.setSlashAmount(BigDecimal.TEN);
        slash.setSlashBlockCount(BigInteger.ONE);
        ari.getSlash().add(slash);
    }
}
