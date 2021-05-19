package com.platon.browser.analyzer.epoch;

import com.platon.contracts.ppos.dto.resp.Node;
import com.alibaba.fastjson.JSON;
import com.platon.browser.v0150.service.RestrictingMinimumReleaseParamService;
import com.platon.browser.bean.AnnualizedRateInfo;
import com.platon.browser.bean.CollectionEvent;
import com.platon.browser.bean.ComplementNodeOpt;
import com.platon.browser.bean.PeriodValueElement;
import com.platon.browser.cache.NetworkStatCache;
import com.platon.browser.config.BlockChainConfig;
import com.platon.browser.dao.entity.GasEstimate;
import com.platon.browser.dao.entity.GasEstimateLog;
import com.platon.browser.dao.entity.Staking;
import com.platon.browser.dao.entity.StakingExample;
import com.platon.browser.dao.mapper.CustomGasEstimateLogMapper;
import com.platon.browser.dao.mapper.EpochBusinessMapper;
import com.platon.browser.dao.mapper.StakingMapper;
import com.platon.browser.dao.param.epoch.Settle;
import com.platon.browser.bean.CustomStaking;
import com.platon.browser.elasticsearch.dto.Block;
import com.platon.browser.elasticsearch.dto.NodeOpt;
import com.platon.browser.exception.BusinessException;
import com.platon.browser.publisher.GasEstimateEventPublisher;
import com.platon.browser.utils.CalculateUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OnSettleAnalyzer {
	
    @Resource
    private BlockChainConfig chainConfig;
    @Resource
    private EpochBusinessMapper epochBusinessMapper;
    @Resource
    private StakingMapper stakingMapper;
    @Resource
    private GasEstimateEventPublisher gasEstimateEventPublisher;
    @Resource
    private CustomGasEstimateLogMapper customGasEstimateLogMapper;
    @Resource
    private NetworkStatCache networkStatCache;
    @Resource
    private RestrictingMinimumReleaseParamService restrictingMinimumReleaseParamService;

    public List<NodeOpt> analyze(CollectionEvent event, Block block) {
        long startTime = System.currentTimeMillis();
        // 操作日志列表
        List<NodeOpt> nodeOpts = new ArrayList<>();
	    if(block.getNum()==1) return nodeOpts;

        log.debug("Block Number:{}",block.getNum());

        Map<String,Node> curVerifierMap = new HashMap<>();
        event.getEpochMessage().getCurVerifierList().forEach(v->curVerifierMap.put(v.getNodeId(),v));
        Map<String,Node> preVerifierMap = new HashMap<>();
        event.getEpochMessage().getPreVerifierList().forEach(v->preVerifierMap.put(v.getNodeId(),v));

        if(event.getEpochMessage().getPreVerifierList().isEmpty()){
            throw new BusinessException("当前周期["+event.getEpochMessage().getSettleEpochRound().intValue()+"]的前一结算周期验证人列表为空！");
        }

        Settle settle = Settle.builder()
                .preVerifierSet(preVerifierMap.keySet())
                .curVerifierSet(curVerifierMap.keySet())
                .stakingReward(event.getEpochMessage().getPreStakeReward())
                .settingEpoch(event.getEpochMessage().getSettleEpochRound().intValue())
                .stakingLockEpoch(chainConfig.getUnStakeRefundSettlePeriodCount().intValue())
                .build();

        List<Integer> statusList = new ArrayList <>();
        statusList.add(CustomStaking.StatusEnum.CANDIDATE.getCode());
        statusList.add(CustomStaking.StatusEnum.EXITING.getCode());
        statusList.add(CustomStaking.StatusEnum.LOCKED.getCode());
        StakingExample stakingExample = new StakingExample();
        stakingExample.createCriteria()
                .andStatusIn(statusList);
        List<Staking> stakingList = stakingMapper.selectByExampleWithBLOBs(stakingExample);
        List<String> exitedNodeIds = new ArrayList<>();
        stakingList.forEach(staking -> {
            //犹豫期金额变成锁定金额
            staking.setStakingLocked(staking.getStakingLocked().add(staking.getStakingHes()));
            staking.setStakingHes(BigDecimal.ZERO);

            //退出中记录状态设置（状态为退出中且已经经过指定的结算周期数，则把状态置为已退出）
            if(
                staking.getStatus() == CustomStaking.StatusEnum.EXITING.getCode() && // 节点状态为退出中
                //(staking.getStakingReductionEpoch() + staking.getUnStakeFreezeDuration()) < settle.getSettingEpoch()
                event.getBlock().getNum()>=staking.getUnStakeEndBlock() // 且当前区块号大于等于质押预计的实际退出区块号
            ){
                staking.setStakingReduction(BigDecimal.ZERO);
                staking.setStatus(CustomStaking.StatusEnum.EXITED.getCode());
                staking.setLowRateSlashCount(0);
                exitedNodeIds.add(staking.getNodeId());
            }

            //锁定中记录状态设置（状态为已锁定中且已经经过指定的结算周期数，则把状态置为候选中）
            if(
                    staking.getStatus() == CustomStaking.StatusEnum.LOCKED.getCode() && // 节点状态为已锁定
                    (staking.getZeroProduceFreezeEpoch() + staking.getZeroProduceFreezeDuration()) < settle.getSettingEpoch()
                     // 且当前区块号大于等于质押预计的实际退出区块号
            ){
                // 低出块处罚次数置0
                staking.setLowRateSlashCount(0);
                // 异常状态
                staking.setExceptionStatus(CustomStaking.ExceptionStatusEnum.NORMAL.getCode());
                // 从已锁定状态恢复到候选中状态
                staking.setStatus(CustomStaking.StatusEnum.CANDIDATE.getCode());
                recoverLog(staking,settle.getSettingEpoch(),block,nodeOpts);
            }

//            // 如果当前节点是因举报而被处罚[exception_status = 5], 则状态直接置为已退出【因为底层实际上已经没有这个节点了】
//            if(staking.getExceptionStatus()== CustomStaking.ExceptionStatusEnum.MULTI_SIGN_SLASHED.getCode()&&
//                    (staking.getStakingReductionEpoch() + staking.getUnStakeFreezeDuration()) < settle.getSettingEpoch()){
//            	staking.setStakingReduction(BigDecimal.ZERO);
//            	staking.setStatus(CustomStaking.StatusEnum.EXITED.getCode());
//            	exitedNodeIds.add(staking.getNodeId());
//            }

            //当前质押是下轮结算周期验证人
            if(settle.getCurVerifierSet().contains(staking.getNodeId())){
                staking.setIsSettle(CustomStaking.YesNoEnum.YES.getCode());
            }else {
                staking.setIsSettle(CustomStaking.YesNoEnum.NO.getCode());
            }

            // 设置发放给当前质押节点的质押奖励金额
            BigDecimal curSettleStakeReward = BigDecimal.ZERO;
            if(settle.getPreVerifierSet().contains(staking.getNodeId())){
                // 如果当前质押节点在上一个结算周期是验证人，则发放的质押奖励是上一个周期算出来的平均质押奖励
                curSettleStakeReward = settle.getStakingReward();
            }

            // ***************** 先设置好：上一结算周期的【委托奖励总和】、【委托成本】 *******************
            // 截至当前结算块号为止，当前质押的总委托数量（先从特殊节点查，特殊节点没有则从staking表相关字段拿）
            Node node = preVerifierMap.get(staking.getNodeId());
            BigDecimal curTotalDelegateCost = BigDecimal.ZERO;
            if(node!=null) {
                // 当前质押节点是前一结算周期验证人，则总委托奖励设置为从特殊节点查出来的总委托奖励
                staking.setTotalDeleReward(new BigDecimal(node.getDelegateRewardTotal()));
                if(BigInteger.ZERO.compareTo(node.getDelegateTotal()) == 0) {
                    // 如果从特殊节点查出来的总委托数量为0，则使用当前质押的【锁定委托+犹豫委托】作为成本
                	curTotalDelegateCost = staking.getStatDelegateLocked().add(staking.getStatDelegateHes());
                } else {
                    // 如果从特殊节点查出来的总委托数量不为0，则使用从特殊节点查出来的委托数作为成本
                	curTotalDelegateCost = new BigDecimal(node.getDelegateTotal());
                }
            } else {
                // 当前质押节点不是前一结算周期验证人，则使用当前质押的【锁定委托+犹豫委托】作为成本
            	curTotalDelegateCost = staking.getStatDelegateLocked().add(staking.getStatDelegateHes());
            }

            // ***************** 再进行质押和委托的年化率计算 *******************
            // 计算节点质押年化率
            calcStakeAnnualizedRate(staking,curSettleStakeReward,settle);
            // 计算委托年化率
            calcDelegateAnnualizedRate(staking,curTotalDelegateCost,settle);

            // 把当前staking的stakingRewardValue的值置为当前结算周期的质押奖励值，累加操作由mapper xmm中的SQL语句完成
            // staking表：【`staking_reward_value` =  `staking_reward_value` + #{staking.stakingRewardValue}】
            // node表：【`stat_staking_reward_value` = `stat_staking_reward_value` + #{staking.stakingRewardValue}】
            staking.setStakingRewardValue(curSettleStakeReward);
        });
        settle.setStakingList(stakingList);
        settle.setExitNodeList(exitedNodeIds);
        epochBusinessMapper.settle(settle);

        List<GasEstimate> gasEstimates = new ArrayList<>();
        preVerifierMap.forEach((k,v)->{
            GasEstimate ge = new GasEstimate();
            ge.setNodeId(v.getNodeId());
            ge.setSbn(v.getStakingBlockNum().longValue());
            gasEstimates.add(ge);
        });

        // 1、把周期数需要自增1的节点质押先入mysql数据库
        Long seq = block.getNum()*10000;
        List<GasEstimateLog> gasEstimateLogs = new ArrayList<>();
        GasEstimateLog gasEstimateLog = new GasEstimateLog();
        gasEstimateLog.setSeq(seq);
        gasEstimateLog.setJson(JSON.toJSONString(gasEstimates));
        gasEstimateLogs.add(gasEstimateLog);
        customGasEstimateLogMapper.batchInsertOrUpdateSelective(gasEstimateLogs,GasEstimateLog.Column.values());

        // 2、发布到操作队列
        gasEstimateEventPublisher.publish(seq,gasEstimates);

        log.debug("处理耗时:{} ms",System.currentTimeMillis()-startTime);

        try {
            // 检查链上生效版本大于等于配置文件中指定的版本，则插入锁仓最小释放金额参数
            restrictingMinimumReleaseParamService.checkRestrictingMinimumReleaseParam(block);
        } catch (Exception e) {
            log.error("检查链上生效版本出错：",e);
        }

        return nodeOpts;
	}

    /**
     * 计算节点质押年化率
     * @param staking 当前质押
     * @param curSettleStakeReward 当前结算周期的质押奖励
     * @param settle 当前结算周期信息
     */
	private void calcStakeAnnualizedRate(Staking staking,BigDecimal curSettleStakeReward,Settle settle){
        // 设置发放质押奖励后的累计质押奖励金额，用于年化率计算
        staking.setStakingRewardValue(staking.getStakingRewardValue().add(curSettleStakeReward));
        // 解析年化率信息对象
        String ariString = staking.getAnnualizedRateInfo();
        AnnualizedRateInfo ari = StringUtils.isNotBlank(ariString)?JSON.parseObject(ariString,AnnualizedRateInfo.class):new AnnualizedRateInfo();


        // 累计质押奖励: 所有（包含当前）结算周期的质押奖励总和
        BigDecimal cumulativeStakeProfit = staking.getStakingRewardValue() // 截至当前结算周期节点的累计质押奖励
                        .add(staking.getBlockRewardValue()) // + 截至当前结算周期节点的累计出块奖励
                        .add(staking.getFeeRewardValue()) // + 截至当前结算周期节点的累计手续费奖励
                        .subtract(staking.getTotalDeleReward()); // - 截至当前结算周期节点的累计委托奖励总和

        // 把当前节点在上一结算周期收益放入轮换信息里
        CalculateUtils.rotateProfit(ari.getStakeProfit(),cumulativeStakeProfit,BigInteger.valueOf(settle.getSettingEpoch()-1L),chainConfig);
        // 计算年化率
        BigDecimal annualizedRate = CalculateUtils.calculateAnnualizedRate(ari.getStakeProfit(),ari.getStakeCost(),chainConfig);
        // 设置年化率
        staking.setAnnualizedRate(annualizedRate.doubleValue());
        // 计算当前质押的年化率 END ******************************



        // 设置下一结算周期节点的质押成本 = 锁定质押 + 犹豫质押
        BigDecimal curSettleStakeCost = staking.getStakingLocked() // 锁定的质押金
                .add(staking.getStakingHes()); // 犹豫期的质押金
        // 轮换下一结算周期的成本信息
        CalculateUtils.rotateCost(ari.getStakeCost(),curSettleStakeCost,BigInteger.valueOf(settle.getSettingEpoch()),chainConfig);



        // 更新年化率计算原始信息
        staking.setAnnualizedRateInfo(ari.toJSONString());
    }

    /**
     * 计算委托年化率
     * @param staking 当前质押记录
     * @param curTotalDelegateCost 节点在当前结算周期的总委托成本
     * @param settle 周期切换业务参数
     */
    private void calcDelegateAnnualizedRate(Staking staking,BigDecimal curTotalDelegateCost,Settle settle){
        // 解析年化率信息对象
        String ariString = staking.getAnnualizedRateInfo();
        AnnualizedRateInfo ari = StringUtils.isNotBlank(ariString)?JSON.parseObject(ariString,AnnualizedRateInfo.class):new AnnualizedRateInfo();

        // 累计委托奖励: 所有（包含当前）结算周期的委托奖励总和
        BigDecimal cumulativeDelegateProfit = staking.getTotalDeleReward();
        // 轮换委托收益信息，把当前节点在上一周期的委托收益放入轮换信息里
        CalculateUtils.rotateProfit(ari.getDelegateProfit(),cumulativeDelegateProfit,BigInteger.valueOf(settle.getSettingEpoch()-1L),chainConfig);
        // 计算年化率
        BigDecimal annualizedRate = CalculateUtils.calculateAnnualizedRate(ari.getDelegateProfit(),ari.getDelegateCost(),chainConfig);
        // 把前一周期的委托奖励年化率设置至preDeleAnnualizedRate字段
        staking.setPreDeleAnnualizedRate(staking.getDeleAnnualizedRate());
        // 设置当前质押记录的委托奖励年化率
        staking.setDeleAnnualizedRate(annualizedRate.doubleValue());
        // 计算当前质押的年化率 END ******************************


        // 设置下一结算周期节点的委托成本
        CalculateUtils.rotateCost(ari.getDelegateCost(),curTotalDelegateCost,BigInteger.valueOf(settle.getSettingEpoch()),chainConfig);


        // 更新年化率计算原始信息
        staking.setAnnualizedRateInfo(ari.toJSONString());
    }

    /**
     * 节点恢复记录日志
     * @param staking
     * @param settingEpoch
     * @param block
     * @param nodeOpts
     */
    private void recoverLog(Staking staking, int settingEpoch, Block block, List<NodeOpt> nodeOpts){
        String desc = NodeOpt.TypeEnum.UNLOCKED.getTpl().replace("LOCKED_EPOCH",staking.getZeroProduceFreezeEpoch().toString())
                .replace("UNLOCKED_EPOCH",String.valueOf(settingEpoch))
                .replace("FREEZE_DURATION",staking.getZeroProduceFreezeDuration().toString());
        NodeOpt nodeOpt = ComplementNodeOpt.newInstance();
        nodeOpt.setId(networkStatCache.getAndIncrementNodeOptSeq());
        nodeOpt.setNodeId(staking.getNodeId());
        nodeOpt.setType(Integer.valueOf(NodeOpt.TypeEnum.UNLOCKED.getCode()));
        nodeOpt.setBNum(block.getNum());
        nodeOpt.setTime(block.getTime());
        nodeOpt.setDesc(desc);
        nodeOpts.add(nodeOpt);
    }
}
