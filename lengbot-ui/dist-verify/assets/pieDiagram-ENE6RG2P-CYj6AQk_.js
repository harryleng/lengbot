import{p as rt}from"./chunk-JWPE2WC7-Bt4eVa8M.js";import{a_ as B,T as nt,aN as it,V as ot,aO as st,Y as lt,aQ as ct,a as d,as as G,X as ut,r as gt,aM as dt,aA as pt,B as ht,s as ft,M as mt}from"./MarkdownPreview-DH84s3cU.js";import{p as vt}from"./cynefin-VYW2F7L2-BySUoT3n.js";import{e as T,a as xt}from"./transform-B0oYUMRy.js";import{d as Z}from"./arc-Cwfcips_.js";import{o as St}from"./ordinal-Cboi1Yqb.js";import"./markdown_preview-gVTxHMD4.js";import"./markdown-vendor-qQm7fHYr.js";import"./index-C0I2KwXp.js";import"./antd-vendor-o-xKdBtR.js";import"./vue-vendor-BEz6SNuH.js";import"./index-Bg5SiUEh.js";import"./clipboard--ATavt94.js";import"./ChatMediaPreview-BiT1CGtu.js";import"./init-Gi6I4Gst.js";function yt(t,n){return n<t?-1:n>t?1:n>=t?0:NaN}function wt(t){return t}function At(){var t=wt,n=yt,y=null,b=T(0),l=T(B),p=T(0);function i(e){var r,s=(e=xt(e)).length,h,w,$=0,f=new Array(s),o=new Array(s),D=+b.apply(this,arguments),z=Math.min(B,Math.max(-B,l.apply(this,arguments)-D)),k,L=Math.min(Math.abs(z)/s,p.apply(this,arguments)),u=L*(z<0?-1:1),A;for(r=0;r<s;++r)(A=o[f[r]=r]=+t(e[r],r,e))>0&&($+=A);for(n!=null?f.sort(function(E,m){return n(o[E],o[m])}):y!=null&&f.sort(function(E,m){return y(e[E],e[m])}),r=0,w=$?(z-s*u)/$:0;r<s;++r,D=k)h=f[r],A=o[h],k=D+(A>0?A*w:0)+u,o[h]={data:e[h],index:r,value:A,startAngle:D,endAngle:k,padAngle:L};return o}return i.value=function(e){return arguments.length?(t=typeof e=="function"?e:T(+e),i):t},i.sortValues=function(e){return arguments.length?(n=e,y=null,i):n},i.sort=function(e){return arguments.length?(y=e,n=null,i):y},i.startAngle=function(e){return arguments.length?(b=typeof e=="function"?e:T(+e),i):b},i.endAngle=function(e){return arguments.length?(l=typeof e=="function"?e:T(+e),i):l},i.padAngle=function(e){return arguments.length?(p=typeof e=="function"?e:T(+e),i):p},i}var Ct=mt.pie,I={sections:new Map,showData:!1},W=I.sections,V=I.showData,$t=structuredClone(Ct),Dt=d(()=>structuredClone($t),"getConfig"),Tt=d(()=>{W=new Map,V=I.showData,ft()},"clear"),bt=d(({label:t,value:n})=>{if(n<0)throw new Error(`"${t}" has invalid value: ${n}. Negative values are not allowed in pie charts. All slice values must be >= 0.`);W.has(t)||(W.set(t,n),G.debug(`added new section: ${t}, with value: ${n}`))},"addSection"),kt=d(()=>W,"getSections"),Mt=d(t=>{V=t},"setShowData"),zt=d(()=>V,"getShowData"),q={getConfig:Dt,clear:Tt,setDiagramTitle:ct,getDiagramTitle:lt,setAccTitle:st,getAccTitle:ot,setAccDescription:it,getAccDescription:nt,addSection:bt,getSections:kt,setShowData:Mt,getShowData:zt},Et=d((t,n)=>{rt(t,n),n.setShowData(t.showData),t.sections.map(n.addSection)},"populateDb"),Rt={parse:d(async t=>{const n=await vt("pie",t);G.debug(n),Et(n,q)},"parse")},Lt=d(t=>`
  .pieCircle{
    stroke: ${t.pieStrokeColor};
    stroke-width : ${t.pieStrokeWidth};
    opacity : ${t.pieOpacity};
  }
  .pieCircle.highlighted{
    scale: 1.05;
    opacity: 1;
  }
  .pieCircle.highlightedOnHover:hover{
    transition-duration: 250ms;
    scale: 1.05;
    opacity: 1;
  }
  .pieOuterCircle{
    stroke: ${t.pieOuterStrokeColor};
    stroke-width: ${t.pieOuterStrokeWidth};
    fill: none;
  }
  .pieTitleText {
    text-anchor: middle;
    font-size: ${t.pieTitleTextSize};
    fill: ${t.pieTitleTextColor};
    font-family: ${t.fontFamily};
  }
  .slice {
    font-family: ${t.fontFamily};
    fill: ${t.pieSectionTextColor};
    font-size:${t.pieSectionTextSize};
    // fill: white;
  }
  .legend text {
    fill: ${t.pieLegendTextColor};
    font-family: ${t.fontFamily};
    font-size: ${t.pieLegendTextSize};
  }
`,"getStyles"),Nt=Lt,Ot=d(t=>{const n=[...t.values()].reduce((l,p)=>l+p,0),y=[...t.entries()].map(([l,p])=>({label:l,value:p})).filter(l=>l.value/n*100>=1);return At().value(l=>l.value).sort(null)(y)},"createPieArcs"),Wt=d((t,n,y,b)=>{var Y;G.debug(`rendering pie chart
`+t);const l=b.db,p=ut(),i=gt(l.getConfig(),p.pie),e=40,r=18,s=4,h=450,w=h,$=dt(n),f=$.append("g");f.attr("transform","translate("+w/2+","+h/2+")");const{themeVariables:o}=p;let[D]=pt(o.pieOuterStrokeWidth);D??(D=2);const z=i.legendPosition,k=i.textPosition,L=i.donutHole>0&&i.donutHole<=.9?i.donutHole:0,u=Math.min(w,h)/2-e,A=Z().innerRadius(L*u).outerRadius(u),E=Z().innerRadius(u*k).outerRadius(u*k),m=f.append("g");m.append("circle").attr("cx",0).attr("cy",0).attr("r",u+D/2).attr("class","pieOuterCircle");const N=l.getSections(),J=Ot(N),K=[o.pie1,o.pie2,o.pie3,o.pie4,o.pie5,o.pie6,o.pie7,o.pie8,o.pie9,o.pie10,o.pie11,o.pie12];let _=0;N.forEach(a=>{_+=a});const U=J.filter(a=>(a.data.value/_*100).toFixed(0)!=="0"),F=St(K).domain([...N.keys()]);m.selectAll("mySlices").data(U).enter().append("path").attr("d",A).attr("fill",a=>F(a.data.label)).attr("class",a=>{let c="pieCircle";return i.highlightSlice==="hover"?c+=" highlightedOnHover":i.highlightSlice===a.data.label&&(c+=" highlighted"),c}),m.selectAll("mySlices").data(U).enter().append("text").text(a=>(a.data.value/_*100).toFixed(0)+"%").attr("transform",a=>"translate("+E.centroid(a)+")").style("text-anchor","middle").attr("class","slice");const tt=f.append("text").text(l.getDiagramTitle()).attr("x",0).attr("y",-400/2).attr("class","pieTitleText"),R=[...N.entries()].map(([a,c])=>({label:a,value:c})),C=f.selectAll(".legend").data(R).enter().append("g").attr("class","legend");C.append("rect").attr("width",r).attr("height",r).style("fill",a=>F(a.label)).style("stroke",a=>F(a.label)),C.append("text").attr("x",r+s).attr("y",r-s).text(a=>l.getShowData()?`${a.label} [${a.value}]`:a.label);const M=Math.max(...C.selectAll("text").nodes().map(a=>(a==null?void 0:a.getBoundingClientRect().width)??0));let O=h,H=w+e;const g=r+s,P=R.length*g;switch(z){case"center":C.attr("transform",(a,c)=>{const v=g*R.length/2,x=-M/2-(r+s),S=c*g-v;return"translate("+x+","+S+")"});break;case"top":O+=P,C.attr("transform",(a,c)=>{const v=u,x=-M/2-(r+s),S=c*g-v;return`translate(${x}, ${S})`}),m.attr("transform",()=>`translate(0, ${P+g})`);break;case"bottom":O+=P,C.attr("transform",(a,c)=>{const v=-u-g,x=-M/2-(r+s),S=c*g-v;return"translate("+x+","+S+")"});break;case"left":H+=r+s+M,C.attr("transform",(a,c)=>{const v=g*R.length/2,x=-u-(r+s),S=c*g-v;return"translate("+x+","+S+")"}),m.attr("transform",()=>`translate(${M+r+s}, 0)`);break;case"right":default:H+=r+s+M,C.attr("transform",(a,c)=>{const v=g*R.length/2,x=12*r,S=c*g-v;return"translate("+x+","+S+")"});break}const X=((Y=tt.node())==null?void 0:Y.getBoundingClientRect().width)??0,et=w/2-X/2,at=w/2+X/2,j=Math.min(0,et),Q=Math.max(H,at)-j;$.attr("viewBox",`${j} 0 ${Q} ${O}`),ht($,O,Q,i.useMaxWidth)},"draw"),_t={draw:Wt},te={parser:Rt,db:q,renderer:_t,styles:Nt};export{te as diagram};
