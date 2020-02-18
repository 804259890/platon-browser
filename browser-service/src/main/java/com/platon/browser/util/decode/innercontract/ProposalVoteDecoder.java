package com.platon.browser.util.decode.innercontract;

import com.platon.browser.param.ProposalVoteParam;
import com.platon.browser.param.TxParam;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;

import java.math.BigInteger;

import static com.platon.browser.util.decode.innercontract.InnerContractDecoder.bigIntegerResolver;
import static com.platon.browser.util.decode.innercontract.InnerContractDecoder.stringResolver;

/**
 * @description: 创建验证人交易输入参数解码器
 * @author: chendongming@juzix.net
 * @create: 2019-11-04 20:13:04
 **/
class ProposalVoteDecoder {
    private ProposalVoteDecoder(){}
    static TxParam decode(RlpList rootList) {
        // 给提案投票
        //投票验证人
        String nodeId = stringResolver((RlpString) rootList.getValues().get(1));
        //提案ID
        String proposalID = stringResolver((RlpString) rootList.getValues().get(2));
        //投票选项
        BigInteger option =  bigIntegerResolver((RlpString) rootList.getValues().get(3));
        //节点代码版本，有rpc的getProgramVersion接口获取
        BigInteger programVersion =  bigIntegerResolver((RlpString) rootList.getValues().get(4));
        //代码版本签名，有rpc的getProgramVersion接口获取
        String versionSign = stringResolver((RlpString) rootList.getValues().get(5));

        return ProposalVoteParam.builder()
                .verifier(nodeId)
                .proposalId(proposalID)
                .option(option.toString())
                .programVersion(programVersion.toString())
                .versionSign(versionSign)
                .build();
    }
}