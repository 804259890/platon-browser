package com.platon.browser.common.enums;

import com.platon.browser.common.dto.ClassName;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;

import java.util.HashMap;
import java.util.Map;

/**
 * User: dongqile
 * Date: 2019/1/9
 * Time: 11:38
 */
public class TransactionType {

    public static final Map<String,Map<Integer,ClassName>> functionNameToParamTypeMap = new HashMap <>();

    public static final Map<Integer,ClassName> voteTicket = new HashMap<>();
    static {
        voteTicket.put(2,new ClassName(Uint64.class,"count"));
        voteTicket.put(3,new ClassName(Uint256.class,"price"));
        voteTicket.put(4,new ClassName(Utf8String.class,"nodeId"));
        functionNameToParamTypeMap.put("VoteTicket",voteTicket);
    }

    public static final Map<Integer,ClassName> candidateDeposit = new HashMap<>();
    static {
        candidateDeposit.put(2,new ClassName(Utf8String.class,"nodeId"));
        candidateDeposit.put(3,new ClassName(Utf8String.class,"owner"));
        candidateDeposit.put(4,new ClassName(Uint32.class,"fee"));
        candidateDeposit.put(5,new ClassName(Utf8String.class,"host"));
        candidateDeposit.put(6,new ClassName(Utf8String.class,"port"));
        candidateDeposit.put(7,new ClassName(Utf8String.class,"Extra"));
    }

    public static final Map<Integer,ClassName> candidateApplyWithdraw = new HashMap<>();
    static {
        candidateApplyWithdraw.put(2,new ClassName(Utf8String.class,"nodeId"));
        candidateApplyWithdraw.put(3,new ClassName(Uint256.class,"withdraw"));
    }

    public static final Map<Integer,ClassName> candidateWithdrawInfos = new HashMap<>();
    static {
        candidateWithdrawInfos.put(2,new ClassName(Utf8String.class,"nodeId"));
    }

    public static final Map<Integer,ClassName> candidateWithdraw = new HashMap<>();
    static {
        candidateWithdraw.put(2,new ClassName(Utf8String.class,"nodeId"));
    }

    public static final Map<Integer,ClassName> setCandidateExtra = new HashMap<>();
    static {
        setCandidateExtra.put(2,new ClassName(Utf8String.class,"nodeId"));
        setCandidateExtra.put(3,new ClassName(Utf8String.class,"Extra"));
    }

    public static final Map<Integer,ClassName> getCorrespondingMap(String type) {
        Map <Integer, ClassName> corMap = new HashMap <>();
        switch (type) {
            case "1000":
                //投票
                //type = "voteTicket";
                corMap = voteTicket;
                break;
            case "1001":
                //竞选质押
                corMap = candidateDeposit;
                break;

            case "1002":
                //减持质押
                corMap = candidateApplyWithdraw;
                break;

            case "1003":
                //提取质押
                corMap = candidateWithdraw;
                break;

        }
        return corMap;
    }
}