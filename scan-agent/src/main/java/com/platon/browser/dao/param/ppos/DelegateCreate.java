package com.platon.browser.dao.param.ppos;

import com.platon.browser.enums.BusinessType;
import com.platon.browser.dao.param.BusinessParam;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.BigInteger;


/**
 * @Auther: dongqile
 * @Date: 2019/11/2
 * @Description: 创建委托 入库参数
 */
@Data
@Builder
@Accessors(chain = true)
public class DelegateCreate implements BusinessParam {
    //节点id
    private String nodeId;
    //委托金额
    private BigDecimal amount;
    //委托交易块高
    private BigInteger blockNumber;
    //交易发送方
    private String txFrom;
    //交易序号
    private BigInteger sequence;
    //节点质押快高
    private BigInteger stakingBlockNumber;

    // 2021/05/19: 年化率计算信息，每个节点刚质押进来时，都可以确定其在下一个结算周期生效时的质押成本和委托成本
    private String annualizedRateInfo;

    @Override
    public BusinessType getBusinessType() {
        return BusinessType.DELEGATE_CREATE;
    }
}
