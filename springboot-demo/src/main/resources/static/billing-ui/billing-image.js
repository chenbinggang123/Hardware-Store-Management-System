/* Deterministic, dependency-free rendering of a confirmed bill to one PNG image. */
(() => {
  'use strict';
  const WIDTH=1400,MARGIN=40,TABLE_WIDTH=WIDTH-MARGIN*2;
  const COLUMNS=[65,300,160,90,120,140,160,285];
  const HEADERS=['序号','商品名称','规格','单位','数量','单价（元）','金额（元）','备注'];
  const FONT='"Microsoft YaHei","Noto Sans CJK SC",Arial,sans-serif';
  const value=v=>v==null||v===''?'':String(v);
  const money=v=>{const n=Number(v);return Number.isFinite(n)?n.toFixed(2):value(v);};
  function font(ctx,size,weight=400){ctx.font=`${weight} ${size}px ${FONT}`;}
  function lines(ctx,text,maxWidth,maxLines=8){
    const result=[];let current='';const source=value(text)||'—';
    for(const character of source){const next=current+character;if(current&&ctx.measureText(next).width>maxWidth){result.push(current);current=character;if(result.length===maxLines-1)break;}else current=next;}
    if(current&&result.length<maxLines)result.push(current);
    if(result.length===maxLines&&source.length>result.join('').length){let last=result[maxLines-1];while(last&&ctx.measureText(last+'…').width>maxWidth)last=last.slice(0,-1);result[maxLines-1]=last+'…';}
    return result.length?result:['—'];
  }
  function centered(ctx,text,x,y,width,height,size=22,weight=400,maxLines=4){
    font(ctx,size,weight);const rows=lines(ctx,text,width-18,maxLines);const step=size*1.5;let top=y+(height-rows.length*step)/2+step*.72;
    ctx.textAlign='center';ctx.textBaseline='alphabetic';for(const row of rows){ctx.fillText(row,x+width/2,top);top+=step;}
  }
  function left(ctx,text,x,y,width,size=20,weight=400,maxLines=3){font(ctx,size,weight);ctx.textAlign='left';ctx.textBaseline='top';const rows=lines(ctx,text,width,maxLines);rows.forEach((row,index)=>ctx.fillText(row,x,y+index*size*1.55));return rows.length*size*1.55;}
  function rule(ctx,x1,y1,x2,y2,width=1){ctx.lineWidth=width;ctx.beginPath();ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.stroke();}
  function rowHeight(ctx,row){font(ctx,22);const values=[row.name,row.specification,row.unit,row.quantity,row.price,row.amount,row.remark];const widths=[COLUMNS[1],COLUMNS[2],COLUMNS[3],COLUMNS[4],COLUMNS[5],COLUMNS[6],COLUMNS[7]];let count=1;for(let i=0;i<values.length;i++)count=Math.max(count,lines(ctx,values[i],widths[i]-18,4).length);return Math.min(150,Math.max(62,18+count*33));}
  function measure(content,ctx){const rowHeights=(content.lines||[]).map(row=>rowHeight(ctx,row));font(ctx,19);const noteLines=lines(ctx,content.remark||'',TABLE_WIDTH-150,4).length;const notes=content.remark?Math.max(48,noteLines*30+14):0;return {rowHeights,notes,height:290+rowHeights.reduce((a,b)=>a+b,0)+58+notes+126};}
  function render(content,options={}){
    if(!content||!Array.isArray(content.lines)||!content.lines.length)throw new Error('没有可生成图片的单据明细');
    const sizingCanvas=document.createElement('canvas'),sizing=sizingCanvas.getContext('2d');const measured=measure(content,sizing);
    const scale=measured.height<=7500?2:1,canvas=document.createElement('canvas');canvas.width=WIDTH*scale;canvas.height=measured.height*scale;
    const ctx=canvas.getContext('2d');if(!ctx)throw new Error('当前浏览器无法生成图片');ctx.scale(scale,scale);ctx.fillStyle='#fff';ctx.fillRect(0,0,WIDTH,measured.height);ctx.fillStyle='#111';ctx.strokeStyle='#333';ctx.lineJoin='miter';
    const company=value(content.companyName)||'本店名称未填写',number=value(options.number)||'—',date=value(content.date)||'—';
    left(ctx,company,MARGIN,35,500,34,700,2);font(ctx,38,700);ctx.textAlign='center';ctx.textBaseline='top';ctx.fillText('销售单',WIDTH/2,30);font(ctx,18);ctx.textAlign='right';ctx.fillText('单号：'+number,WIDTH-MARGIN,42);rule(ctx,MARGIN,92,WIDTH-MARGIN,92,2);
    left(ctx,'联系电话：'+(value(content.phone)||'未填写'),MARGIN,108,520,19);left(ctx,'单据日期：'+date,WIDTH-360,108,320,19);rule(ctx,MARGIN,143,WIDTH-MARGIN,143);
    left(ctx,'购货单位：'+(value(content.customerName)||'未填写'),MARGIN,158,560,20);left(ctx,'经手人：',620,158,250,20);left(ctx,'备注：'+(value(content.remark)||'无'),910,158,WIDTH-MARGIN-910,20,400,2);rule(ctx,MARGIN,214,WIDTH-MARGIN,214);
    let y=214,x=MARGIN;const headerHeight=58;ctx.fillStyle='#f5f5f2';ctx.fillRect(MARGIN,y,TABLE_WIDTH,headerHeight);ctx.fillStyle='#111';
    for(let i=0;i<COLUMNS.length;i++){centered(ctx,HEADERS[i],x,y,COLUMNS[i],headerHeight,20,600,2);rule(ctx,x,y,x,y+headerHeight);x+=COLUMNS[i];}rule(ctx,WIDTH-MARGIN,y,WIDTH-MARGIN,y+headerHeight);rule(ctx,MARGIN,y,WIDTH-MARGIN,y);rule(ctx,MARGIN,y+headerHeight,WIDTH-MARGIN,y+headerHeight);y+=headerHeight;
    content.lines.forEach((row,index)=>{const height=measured.rowHeights[index];x=MARGIN;const values=[index+1,row.name,row.specification,row.unit,row.quantity,money(row.price),money(row.amount),row.remark];for(let i=0;i<COLUMNS.length;i++){centered(ctx,values[i],x,y,COLUMNS[i],height,20,400,i===1||i===7?4:3);rule(ctx,x,y,x,y+height);x+=COLUMNS[i];}rule(ctx,WIDTH-MARGIN,y,WIDTH-MARGIN,y+height);rule(ctx,MARGIN,y+height,WIDTH-MARGIN,y+height);y+=height;});
    const labelWidth=COLUMNS.slice(0,6).reduce((a,b)=>a+b,0);centered(ctx,'合计',MARGIN,y,labelWidth,58,22,600);centered(ctx,money(options.total),MARGIN+labelWidth,y,COLUMNS[6],58,22,700);centered(ctx,'人民币',MARGIN+labelWidth+COLUMNS[6],y,COLUMNS[7],58,19);rule(ctx,MARGIN,y,WIDTH-MARGIN,y);rule(ctx,MARGIN,y+58,WIDTH-MARGIN,y+58,2);rule(ctx,MARGIN,y,MARGIN,y+58);rule(ctx,MARGIN+labelWidth,y,MARGIN+labelWidth,y+58);rule(ctx,MARGIN+labelWidth+COLUMNS[6],y,MARGIN+labelWidth+COLUMNS[6],y+58);rule(ctx,WIDTH-MARGIN,y,WIDTH-MARGIN,y+58);y+=72;
    if(content.remark){const used=left(ctx,'整单备注：'+content.remark,MARGIN,y,TABLE_WIDTH,19,400,4);y+=Math.max(used+10,measured.notes);}
    const hasSource=(content.lines||[]).some(row=>value(row.sourceAmount));left(ctx,hasSource?'金额按最终确认的数量与单价计算；来源金额和核对信息保留在 Excel 中。':'金额按最终确认的数量与单价计算。',MARGIN,y,TABLE_WIDTH,17);y+=34;
    left(ctx,'本单仅为标准单据，尚未登记销售、欠款或库存。',MARGIN,y,TABLE_WIDTH,17);y+=48;
    left(ctx,'经手人：________________',MARGIN,y,430,20);font(ctx,20);ctx.textAlign='right';ctx.fillText('客户签收：________________',WIDTH-MARGIN,y);
    return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve({blob,width:canvas.width,height:canvas.height,fileName:(`销售单_${number}.png`).replace(/[\\/:*?"<>|]/g,'_')}):reject(new Error('图片生成失败，请重试')),'image/png'));
  }
  window.BillingImage=Object.freeze({render});
})();
