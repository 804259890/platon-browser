package com.platon.browser.analyzer.ppos;

import com.platon.browser.bean.AnnualizedRateInfo;
import com.platon.browser.bean.CollectionEvent;
import com.platon.browser.bean.ComplementNodeOpt;
import com.platon.browser.bean.PeriodValueElement;
import com.platon.browser.cache.NetworkStatCache;
import com.platon.browser.dao.mapper.StakeBusinessMapper;
import com.platon.browser.dao.param.ppos.StakeCreate;
import com.platon.browser.elasticsearch.dto.NodeOpt;
import com.platon.browser.elasticsearch.dto.Transaction;
import com.platon.browser.enums.ModifiableGovernParamEnum;
import com.platon.browser.exception.BusinessException;
import com.platon.browser.param.StakeCreateParam;
import com.platon.browser.service.govern.ParameterService;
import com.platon.browser.service.ppos.StakeEpochService;
import com.platon.browser.utils.ChainVersionUtil;
import com.platon.browser.utils.DateUtil;
import com.platon.browser.utils.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;


/**
 * @description: 创建验证人(质押)业务参数转换器
 * @author: chendongming@matrixelements.com
 * @create: 2019-11-04 17:58:27
 **/
@Slf4j
@Service
public class StakeCreateAnalyzer extends PPOSAnalyzer<NodeOpt> {
	
    @Resource
    private StakeBusinessMapper stakeBusinessMapper;
    @Resource
    private NetworkStatCache networkStatCache;
    @Resource
	private ParameterService parameterService;
    @Resource
	private StakeEpochService stakeEpochService;

    @Override
    public NodeOpt analyze(CollectionEvent event, Transaction tx) {
		// 失败的交易不分析业务数据
		if(Transaction.StatusEnum.FAILURE.getCode()==tx.getStatus()) return null;

		long startTime = System.currentTimeMillis();

        StakeCreateParam txParam = tx.getTxParam(StakeCreateParam.class);
        BigInteger bigVersion = ChainVersionUtil.toBigVersion(txParam.getProgramVersion());
        BigInteger stakingBlockNum = BigInteger.valueOf(tx.getNum());

        String configVal = parameterService.getValueInBlockChainConfig(ModifiableGovernParamEnum.UN_STAKE_FREEZE_DURATION.getName());
        if(StringUtils.isBlank(configVal)){
        	throw new BusinessException("参数表参数缺失："+ModifiableGovernParamEnum.UN_STAKE_FREEZE_DURATION.getName());
		}
        Date txTime = DateUtil.covertTime(tx.getTime());
		// 更新解质押到账需要经过的结算周期数
		BigInteger  unStakeFreezeDuration = stakeEpochService.getUnStakeFreeDuration();
		// 理论上的退出区块号
		BigInteger unStakeEndBlock = stakeEpochService.getUnStakeEndBlock(txParam.getNodeId(),event.getEpochMessage().getSettleEpochRound(),false);

		StakeCreate businessParam= StakeCreate.builder()
        		.nodeId(txParam.getNodeId())
        		.stakingHes(txParam.getAmount())
        		.nodeName(txParam.getNodeName())
        		.externalId(txParam.getExternalId())
        		.benefitAddr(txParam.getBenefitAddress())
        		.programVersion(txParam.getProgramVersion().toString())
        		.bigVersion(bigVersion.toString())
        		.webSite(txParam.getWebsite())
        		.details(txParam.getDetails())
        		.isInit(isInit(txParam.getBenefitAddress())) 
        		.stakingBlockNum(stakingBlockNum)
        		.stakingTxIndex(tx.getIndex())
        		.stakingAddr(tx.getFrom())
        		.joinTime(txTime)
        		.txHash(tx.getHash())
				.delegateRewardPer(txParam.getDelegateRewardPer())
				.unStakeFreezeDuration(unStakeFreezeDuration.intValue())
				.unStakeEndBlock(unStakeEndBlock)
				.settleEpoch(event.getEpochMessage().getSettleEpochRound().intValue())
                .build();


// 2020/05/19 14:39:10 START*************************************************************************
		// 年化率计算信息，每个节点刚质押进来时，都可以确定其在下一个结算周期生效时的质押成本
		Long nextSettleEpochRound = event.getEpochMessage().getSettleEpochRound().longValue()+1;
		AnnualizedRateInfo ari = new AnnualizedRateInfo();
		// 节点在下一结算周期的质押成本, 由于是刚质押进来，所以取当前结算周期只有犹豫期金额（即质押交易的参数值）
		BigDecimal nextSettleStakeCost = businessParam.getStakingHes();
		ari.getStakeCost().add(new PeriodValueElement().setPeriod(nextSettleEpochRound).setValue(nextSettleStakeCost));
		businessParam.setAnnualizedRateInfo(ari.toJSONString());
// 2020/05/19 14:39:10 END*************************************************************************

        stakeBusinessMapper.create(businessParam);
        
        updateNodeCache(HexUtil.prefix(txParam.getNodeId()),txParam.getNodeName(),stakingBlockNum);
        
        NodeOpt nodeOpt = ComplementNodeOpt.newInstance();
        nodeOpt.setId(networkStatCache.getAndIncrementNodeOptSeq());
		nodeOpt.setNodeId(txParam.getNodeId());
		nodeOpt.setType(Integer.valueOf(NodeOpt.TypeEnum.CREATE.getCode()));
		nodeOpt.setTxHash(tx.getHash());
		nodeOpt.setBNum(tx.getNum());
		nodeOpt.setTime(txTime);

		log.debug("处理耗时:{} ms",System.currentTimeMillis()-startTime);

        return nodeOpt;
    }
}
