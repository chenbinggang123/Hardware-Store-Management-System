package com.example.demo.agent.model;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.example.demo.billing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionSourceGuardExperimentTest {
    private static final String OCR="[表格 1]\n第1行\t列1=项 目\t列2=单位\t列3=数量 单价\n第3行\t列1=气动\t列2=2\t列4=130\t列8=30\n第4行\t列1=√磨棒\t列3=100支\t列4=0.65\n第5行\t列1=钻头\t列3=1 80\t列4=5、8\n";
    private Map<String,Object> validate(Map<String,Object> row){return new RecognitionSourceGuardExperiment(OCR).validate(Map.of("documentType","PURCHASE","lines",List.of(row)));}
    private Map<String,Object> row(Map<String,Object> args){return ((List<Map<String,Object>>)args.get("lines")).get(0);}
    @Test void misplacedNumbersBecomeCandidatesWithoutInventingReplacements(){
        var args=validate(Map.of("name","气动","rawName","气动","quantity",130,"price",30));var row=row(args);
        assertNull(row.get("quantity"));assertNull(row.get("price"));
        assertTrue(row.get("remark").toString().contains("数量候选130"));assertTrue(row.get("remark").toString().contains("单价候选30"));
        assertTrue(args.get("sourceNote").toString().contains("列4=130"));
    }
    @Test void verifiedValuesAndAttachedUnitsAreRetained(){
        var args=validate(Map.of("name","磨棒","rawName","磨棒","quantity",100,"price",.65));
        assertEquals(100,row(args).get("quantity"));assertEquals(.65,row(args).get("price"));
    }
    @Test void decimalGuessesAndSpacedNumbersAreNotSilentlyAdopted(){
        var args=validate(Map.of("name","钻头","rawName","钻头","quantity",180,"price",5.8));
        assertNull(row(args).get("quantity"));assertNull(row(args).get("price"));
        assertTrue(args.get("sourceNote").toString().contains("1 80"));assertTrue(args.get("sourceNote").toString().contains("5、8"));
    }
    @Test void omittedDateStaysEmptyThroughTheActualBillingNormalizer(){
        var args=validate(Map.of("name","磨棒","rawName","磨棒","quantity",100,"price",.65));
        assertEquals("",args.get("date"));
        var mapper=new ObjectMapper();var tool=new PrepareBillingDocumentTool(new BillingService(null,null,null,null,mapper),mapper);
        var preview=(Map<?,?>)tool.approvalPreview(new AgentToolContext(1L,7L),args);
        assertEquals("",((BillingContent)preview.get("content")).getDate());
    }
    @Test void explicitDateIsNotReplacedAndInputsAreNotMutated(){
        var original=new LinkedHashMap<String,Object>();original.put("date","2026-10-06");original.put("lines",List.of(Map.of("name","磨棒","rawName","磨棒","quantity",100,"price",.65)));
        assertEquals("2026-10-06",new RecognitionSourceGuardExperiment(OCR).validate(original).get("date"));
        assertFalse(original.containsKey("sourceNote"));
    }
    @Test void unlocatableNumbersRemainCandidatesAndNoBlankDraftIsReportedAsSuccess(){
        var args=validate(Map.of("name","不存在","rawName","不存在","quantity",10,"price",5));assertNull(row(args).get("quantity"));assertNull(row(args).get("price"));
        assertThrows(IllegalArgumentException.class,()->validate(Map.of("name","")));
    }
    @Test void mergedHeaderCanMixSimplifiedAndTraditionalCharacters(){
        var guard=new RecognitionSourceGuardExperiment(OCR.replace("数量 单价","数量 單價"));
        var args=guard.validate(Map.of("lines",List.of(Map.of("name","磨棒","rawName","磨棒","quantity",100,"price",.65))));
        assertEquals(100,row(args).get("quantity"));assertEquals(.65,row(args).get("price"));
    }
}
