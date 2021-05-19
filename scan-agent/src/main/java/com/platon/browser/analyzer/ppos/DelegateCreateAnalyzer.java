package com.platon.browser.analyzer.ppos;

import com.alibaba.fastjson.JSON;
import com.platon.browser.bean.AnnualizedRateInfo;
import com.platon.browser.bean.CollectionEvent;
import com.platon.browser.bean.PeriodValueElement;
import com.platon.browser.dao.entity.GasEstimate;
import com.platon.browser.dao.entity.Staking;
import com.platon.browser.dao.entity.StakingExample;
import com.platon.browser.dao.mapper.CustomGasEstimateMapper;
import com.platon.browser.dao.mapper.DelegateBusinessMapper;
import com.platon.browser.dao.mapper.StakingMapper;
import com.platon.browser.dao.param.ppos.DelegateCreate;
import com.platon.browser.elasticsearch.dto.Transaction;
import com.platon.browser.exception.BusinessException;
import com.platon.browser.param.DelegateCreateParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * @description: 委托业务参数转换器
 * @author: chendongming@matrixelements.com
 * @create: 2019-11-04 17:58:27
 **/
@Slf4j
@Service
public class DelegateCreateAnalyzer extends PPOSAnalyzer<DelegateCreate> {
    @Resource
    private StakingMapper stakingMapper;
    @Resource
    private DelegateBusinessMapper delegateBusinessMapper;
    @Resource
    private CustomGasEstimateMapper customGasEstimateMapper;

    @Override
    public DelegateCreate analyze(CollectionEvent event, Transaction tx) {
        // 发起委托
        DelegateCreateParam txParam = tx.getTxParam(DelegateCreateParam.class);
        // 补充节点名称
        updateTxInfo(txParam,tx);
        // 失败的交易不分析业务数据
        if(Transaction.StatusEnum.FAILURE.getCode()==tx.getStatus()) return null;

        long startTime = System.currentTimeMillis();

        DelegateCreate businessParam= DelegateCreate.builder()
        		.nodeId(txParam.getNodeId())
        		.amount(txParam.getAmount())
        		.blockNumber(BigInteger.valueOf(tx.getNum()))
        		.txFrom(tx.getFrom())
        		.sequence(BigInteger.valueOf(tx.getSeq()))
        		.stakingBlockNumber(txParam.getStakingBlockNum())
                .build();





// 2020/05/19 14:39:10 START*************************************************************************
        // 取出对应的质押记录
        StakingExample condition = new StakingExample();
        condition.createCriteria().andNodeIdEqualTo(businessParam.getNodeId())
                .andStakingBlockNumEqualTo(businessParam.getStakingBlockNumber().longValue());
        List<Staking> stakingList = stakingMapper.selectByExample(condition);
        if(stakingList.isEmpty()) {
            throw new BusinessException("找不到对应的质押记录【nodeId="+businessParam.getNodeId()+",stakingBlockNum="+businessParam.getStakingBlockNumber()+"】");
        }

        Staking staking = stakingList.get(0);
        // 年化率计算信息，当前结算周期委托进来的金额都可以算作节点在下一个结算周期生效时的委托成本
        Long nextSettleEpochRound = event.getEpochMessage().getSettleEpochRound().longValue()+1;
        AnnualizedRateInfo ari = JSON.parseObject(staking.getAnnualizedRateInfo(),AnnualizedRateInfo.class);
        // 节点在下一结算周期的委托成本, 由于是刚委托进来，所以取当前结算周期只有犹豫期金额（即质委托交易的参数值）累加
        List<PeriodValueElement> delegateCostList = ari.getDelegateCost();
        if(delegateCostList.isEmpty()){
            // 如果下一周期的委托成本还未添加，则新加一条
            PeriodValueElement pve = new PeriodValueElement().setPeriod(nextSettleEpochRound).setValue(businessParam.getAmount());
            delegateCostList.add(pve);
        }
        if(!delegateCostList.isEmpty()){
            delegateCostList.forEach(pve->{
                if(pve.getPeriod().longValue()==nextSettleEpochRound){
                    // 如果下一周期的委托成本已有记录，则累加目标周期的委托成本
                    pve.setValue(pve.getValue().add(businessParam.getAmount()));
                }
            });
        }
        // 年化信息更新回填
        businessParam.setAnnualizedRateInfo(ari.toJSONString());

// 2020/05/19 14:39:10  END*************************************************************************




        delegateBusinessMapper.create(businessParam);

        // 1. 新增 估算gas委托未计算周期 epoch = 0: 直接入库到mysql数据库
        List<GasEstimate> estimates = new ArrayList<>();
        GasEstimate estimate = new GasEstimate();
        estimate.setNodeId(txParam.getNodeId());
        estimate.setSbn(txParam.getStakingBlockNum().longValue());
        estimate.setAddr(tx.getFrom());
        estimate.setEpoch(0L);
        estimates.add(estimate);
        customGasEstimateMapper.batchInsertOrUpdateSelective(estimates, GasEstimate.Column.values());

        log.debug("处理耗时:{} ms",System.currentTimeMillis()-startTime);
        return businessParam;
    }
}
