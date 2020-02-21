package com.platon.browser.common.collection.dto;

import com.platon.browser.client.PlatOnClient;
import com.platon.browser.client.Receipt;
import com.platon.browser.common.complement.cache.AddressCache;
import com.platon.browser.elasticsearch.dto.Transaction;
import com.platon.browser.enums.ContractDescEnum;
import com.platon.browser.enums.ContractTypeEnum;
import com.platon.browser.enums.InnerContractAddrEnum;
import com.platon.browser.exception.BeanCreateOrUpdateException;
import com.platon.browser.param.DelegateExitParam;
import com.platon.browser.param.DelegateRewardClaimParam;
import com.platon.browser.util.decode.generalcontract.GeneralContractDecodeUtil;
import com.platon.browser.util.decode.generalcontract.GeneralContractDecodedResult;
import com.platon.browser.util.decode.innercontract.InnerContractDecodeUtil;
import com.platon.browser.util.decode.innercontract.InnerContractDecodedResult;
import com.platon.sdk.contracts.ppos.dto.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.PlatonGetCode;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Slf4j
public class CollectionTransaction extends Transaction {
    private CollectionTransaction(){}
    public static CollectionTransaction newInstance(){
        Date date = new Date();
        CollectionTransaction transaction = new CollectionTransaction();
        transaction.setCreTime(date)
            .setUpdTime(date)
            .setCost(BigDecimal.ZERO.toString())
            .setGasLimit(BigDecimal.ZERO.toString())
            .setGasPrice(BigDecimal.ZERO.toString())
            .setGasUsed(BigDecimal.ZERO.toString())
            .setStatus(StatusEnum.FAILURE.getCode())
            .setValue(BigDecimal.ZERO.toString())
            .setIndex(0);
        return transaction;
    }

    CollectionTransaction updateWithBlock(CollectionBlock block){
        this.setTime(block.getTime());
        return this;
    }

    CollectionTransaction updateWithRawTransaction(org.web3j.protocol.core.methods.response.Transaction transaction){
        this.setNum(transaction.getBlockNumber().longValue())
            .setBHash(transaction.getBlockHash())
            .setHash(transaction.getHash())
            .setValue(transaction.getValue().toString())
            .setIndex(transaction.getTransactionIndex().intValue())
            .setGasPrice(transaction.getGasPrice().toString())
            .setInput(transaction.getInput())
            .setTo(transaction.getTo())
            .setFrom(transaction.getFrom())
            .setGasLimit(transaction.getGas().toString())
            .setNonce(transaction.getNonce().toString());
        return this;
    }

    static class ComplementInfo{
        // 交易类型
        Integer type=null;
        Integer toType=null;
        // 合约代码
        String binCode = null;
        // 合约方法
        String method = null;
        // 合约类型
        Integer contractType = null;
        // tx info信息
        String info = "{}";
    }
    CollectionTransaction updateWithBlockAndReceipt(CollectionBlock block, Receipt receipt, PlatOnClient platOnClient, AddressCache addressCache) throws BeanCreateOrUpdateException {


        //============需要通过解码补充的交易信息============
        ComplementInfo ci = new ComplementInfo();
        String inputWithoutPrefix = StringUtils.isNotBlank(getInput())?getInput().replace("0x",""):"";
        if(InnerContractAddrEnum.getAddresses().contains(getTo())&&StringUtils.isNotBlank(inputWithoutPrefix)){
            // 如果to地址是内置合约地址，则解码交易输入
            resolveInnerContractInvokeTxComplementInfo(receipt.getLogs(),ci);
        }else{
            if(StringUtils.isBlank(getTo())) {
                // 如果to地址为空则是普通合约创建
                resolveGeneralContractCreateTxComplementInfo(receipt.getContractAddress(),platOnClient,ci);
                // 把回执里的合约地址回填到交易的to字段
                setTo(receipt.getContractAddress());
            }else{
                if(addressCache.isGeneralContractAddress(getTo())&&inputWithoutPrefix.length()>=8){
                    // 如果是普通合约调用（EVM||WASM）
                    resolveGeneralContractInvokeTxComplementInfo(platOnClient,ci,addressCache);
                }else {
                    BigInteger value = StringUtils.isNotBlank(getValue())?new BigInteger(getValue()):BigInteger.ZERO;
                    if(value.compareTo(BigInteger.ZERO)>=0){
                        // 如果输入为空且value大于0，则是普通转账
                        resolveGeneralTransferTxComplementInfo(ci,addressCache);
                    }
                }
            }
        }
        
        if(ci.type==null){
            throw new BeanCreateOrUpdateException("交易类型为空,遇到未知交易:[blockNumber="+getNum()+",txHash="+getHash()+"]");
        }
        if(ci.toType==null){
            throw new BeanCreateOrUpdateException("To地址为空:[blockNumber="+getNum()+",txHash="+getHash()+"]");
        }
        // 默认取状态字段作为交易成功与否的状态
        int status = receipt.getStatus();
        if (InnerContractAddrEnum.getAddresses().contains(getTo()) && ci.type.intValue() != TypeEnum.TRANSFER.getCode()) {
            // 如果接收者为内置合约且不为转账, 取日志中的状态作为交易成功与否的状态
            status=receipt.getLogStatus();
        }

        // 交易信息
        this.setGasUsed(receipt.getGasUsed().toString())
                .setCost(decimalGasUsed().multiply(decimalGasPrice()).toString())
                .setFailReason(receipt.getFailReason())
                .setStatus(status)
                .setSeq(getNum()*10000+getIndex())
                .setInfo(ci.info)
                .setType(ci.type)
                .setToType(ci.toType)
                .setContractAddress(receipt.getContractAddress())
                .setContractType(ci.contractType)
                .setBin(ci.binCode)
                .setMethod(ci.method);

        // 累加总交易数
        block.setTxQty(block.getTxQty()+1);
        // 累加具体业务交易数
        switch (getTypeEnum()){
            case TRANSFER: // 转账交易，from地址转账交易数加一
                block.setTranQty(block.getTranQty()+1);
                break;
            case STAKE_CREATE:// 创建验证人
            case STAKE_INCREASE:// 增加自有质押
            case STAKE_MODIFY:// 编辑验证人
            case STAKE_EXIT:// 退出验证人
            case REPORT:// 举报验证人
                block.setSQty(block.getSQty()+1);
                break;
            case DELEGATE_CREATE:// 发起委托
                block.setDQty(block.getDQty()+1);
                break;
            case DELEGATE_EXIT:// 撤销委托
                if(status==Receipt.getSuccess()){
                    // 成功的领取交易才解析info回填
                    // 设置委托奖励提取额
                    DelegateExitParam param = getTxParam(DelegateExitParam.class);
                    BigDecimal reward = new BigDecimal(getDelegateReward(receipt.getLogs()));
                    param.setReward(reward);
                    setInfo(param.toJSONString());
                }
                block.setDQty(block.getDQty()+1);
                break;
            case CLAIM_REWARDS: // 领取委托奖励
                DelegateRewardClaimParam param = DelegateRewardClaimParam.builder()
                        .rewardList(new ArrayList<>())
                        .build();
                if(status==Receipt.getSuccess()){
                    // 成功的领取交易才解析info回填
                    param = getTxParam(DelegateRewardClaimParam.class);
                }
                setInfo(param.toJSONString());
                block.setDQty(block.getDQty()+1);
                break;
            case PROPOSAL_TEXT:// 创建文本提案
            case PROPOSAL_UPGRADE:// 创建升级提案
            case PROPOSAL_PARAMETER:// 创建参数提案
            case PROPOSAL_VOTE:// 提案投票
            case PROPOSAL_CANCEL:// 取消提案
            case VERSION_DECLARE:// 版本声明
                block.setPQty(block.getPQty()+1);
                break;
            default:
        }
        // 累加当前交易的手续费到当前区块的txFee
        block.setTxFee(block.decimalTxFee().add(decimalCost()).toString());
        // 累加当前交易的能量限制到当前区块的txGasLimit
        block.setTxGasLimit(block.decimalTxGasLimit().add(decimalGasLimit()).toString());
        return this;
    }

    /**
     *  获得解除委托时所提取的委托收益
     */
    private BigInteger getDelegateReward(List<Log> logs) {
        if(logs==null||logs.isEmpty()) return BigInteger.ZERO;

        String logData = logs.get(0).getData();
        if(null == logData || "".equals(logData) ) return BigInteger.ZERO;

        RlpList rlp = RlpDecoder.decode(Numeric.hexStringToByteArray(logData));
        List<RlpType> rlpList = ((RlpList)(rlp.getValues().get(0))).getValues();
        String decodedStatus = new String(((RlpString)rlpList.get(0)).getBytes());
        int statusCode = Integer.parseInt(decodedStatus);

        if(statusCode != ErrorCode.SUCCESS) return BigInteger.ZERO;

        return ((RlpString)(RlpDecoder.decode(((RlpString)rlpList.get(1)).getBytes())).getValues().get(0)).asPositiveBigInteger();
    }

    /**
     * 内置合约调用交易,解析补充信息
     */
    private void resolveInnerContractInvokeTxComplementInfo(List<Log> logs,ComplementInfo ci) throws BeanCreateOrUpdateException {
        InnerContractDecodedResult decodedResult;
        try {
            // 解析交易的输入及交易回执log信息
            decodedResult = InnerContractDecodeUtil.decode(getInput(),logs);
            ci.type = decodedResult.getTypeEnum().getCode();
            ci.info = decodedResult.getParam().toJSONString();
            ci.toType = ToTypeEnum.INNER_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.INNER.getCode();
            ci.method = null;
            ci.binCode = null;
        } catch (Exception e) {
            throw new BeanCreateOrUpdateException("交易[hash:" + this.getHash() + "]的参数解析出错:" + e.getMessage());
        }
    }


    private String getContractBinCode(PlatOnClient platOnClient,String contractAddress) throws BeanCreateOrUpdateException {
        try {
            PlatonGetCode platonGetCode = platOnClient.getWeb3jWrapper().getWeb3j().platonGetCode(contractAddress,
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(getNum()))).send();
            return platonGetCode.getCode();
        }catch (Exception e){
            platOnClient.updateCurrentWeb3jWrapper();
            String error = "获取合约代码出错["+contractAddress+"]:"+e.getMessage();
            log.error("{}",error);
            throw new BeanCreateOrUpdateException(error);
        }
    }

    /**
     * 创建普通合约交易,解析补充信息
     * @param contractAddress
     * @param ci
     * @throws IOException
     */
    private void resolveGeneralContractCreateTxComplementInfo(String contractAddress, PlatOnClient platOnClient, ComplementInfo ci) throws BeanCreateOrUpdateException {
        ci.info="";
        ci.binCode = getContractBinCode(platOnClient,contractAddress);
        //解码合约创建交易前缀，用于区分EVM||WASM
        GeneralContractDecodedResult decodedResult = GeneralContractDecodeUtil.decode(getInput());
        ci.type = decodedResult.getTypeEnum().getCode();
        if(decodedResult.getTypeEnum()==TypeEnum.EVM_CONTRACT_CREATE){
            ci.toType = ToTypeEnum.EVM_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.EVM.getCode();
        }
        if(decodedResult.getTypeEnum()==TypeEnum.WASM_CONTRACT_CREATE){
            ci.toType = ToTypeEnum.WASM_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.WASM.getCode();
        }
    }

    /**
     * 调用普通合约交易,解析补充信息
     * @param ci
     * @throws IOException
     */
    private void resolveGeneralContractInvokeTxComplementInfo(PlatOnClient platOnClient,ComplementInfo ci,AddressCache addressCache) throws BeanCreateOrUpdateException {
        ci.info="";
        ci.binCode = getContractBinCode(platOnClient,getTo());
        // TODO: 解析出调用合约方法名
        String txInput = getInput();
//        ci.method = getGeneralContractMethod();
        if(addressCache.isEvmContractAddress(getTo())){
            ci.toType = ToTypeEnum.EVM_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.EVM.getCode();
        }
        if(addressCache.isWasmContractAddress(getTo())){
            ci.toType = ToTypeEnum.WASM_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.WASM.getCode();
        }
        ci.type = TypeEnum.CONTRACT_EXEC.getCode();
    }

    /**
     * 发起普通交易,解析补充信息
     * @param ci
     */
    private void resolveGeneralTransferTxComplementInfo(ComplementInfo ci,AddressCache addressCache){
        ci.type = TypeEnum.TRANSFER.getCode();
        ci.contractType = null;
        ci.method = null;
        ci.info = "{}";
        ci.binCode = null;
        // 需要根据交易的to地址是否是什么类型的地址
        if(InnerContractAddrEnum.getAddresses().contains(getTo())) {
        	ci.toType = ToTypeEnum.INNER_CONTRACT.getCode();
        	ci.contractType = ContractTypeEnum.INNER.getCode();
        	ci.method = ContractDescEnum.getMap().get(getTo()).getContractName();
        } else if (addressCache.isEvmContractAddress(getTo())) {
        	ci.toType = ToTypeEnum.EVM_CONTRACT.getCode();
        	ci.contractType = ContractTypeEnum.EVM.getCode();
        } else if (addressCache.isWasmContractAddress(getTo())) {
            ci.toType = ToTypeEnum.WASM_CONTRACT.getCode();
            ci.contractType = ContractTypeEnum.WASM.getCode();
        } else {
        	ci.toType = ToTypeEnum.ACCOUNT.getCode();
        }
    }
}
