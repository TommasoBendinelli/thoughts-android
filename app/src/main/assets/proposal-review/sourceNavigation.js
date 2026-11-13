"use strict";

// All offsets are UTF-16, as used by Kotlin strings and textarea selections.
// A PDF line is meaningful only in the immutable source which built that PDF.
const SOURCE_LOCATION_MISSING = "The selected passage could not be located in the current source.";
function resolveSourceNavigation(base, current, selectedText, mappedLine, pdfContext = "", filename = "source.tex") {
  const unresolved = detail => ({status:"unresolved", message:SOURCE_LOCATION_MISSING + (detail ? " " + detail : "")});
  if (typeof base !== "string") return unresolved("The source version used to build this PDF is unavailable. Rebuild and sync, or locate the passage manually.");
  const model = require("./proposal-model");
  const lines = text => {
    let start=0;
    return text.split("\n").map(value => {const row={start,end:start+value.length,text:value};start+=value.length+1;return row;});
  };
  const oldLines=lines(base), newLines=base===current?oldLines:lines(current);
  const line=(Number.isInteger(mappedLine)?mappedLine:1)-1;
  const originLine=oldLines[line];
  const projection=text=>{
    let value="",offsets=[],space=false;
    for(let i=0;i<text.length;i++) {
      if(/\s/u.test(text[i])) {if(!space){value+=" ";offsets.push(i);}space=true;}
      else {value+=text[i];offsets.push(i);space=false;}
    }
    offsets.push(text.length);return {value,offsets};
  };
  const needle=String(selectedText||"").trim().replace(/\s+/gu," ");
  function matches(text, phrase, start=0, end=text.length) {
    if(phrase.length<3)return [];
    const p=projection(text.slice(start,end)), result=[];
    let at=p.value.indexOf(phrase);
    while(at>=0){
      result.push({start:start+p.offsets[at],end:start+p.offsets[at+phrase.length]});
      if(result.length>128)return []; // Ambiguous repeated text; never guess.
      at=p.value.indexOf(phrase,at+phrase.length);
    }
    return result;
  }
  let origin;
  // A word elsewhere in the file is not evidence for the clicked PDF location.
  // Only refine within the reference resolver's source line, verified by the
  // surrounding PDF line. Strip a conservative subset of TeX presentation syntax.
  const plain=s=>s.replace(/\\[a-zA-Z]+\*?(?:\[[^\]\n]*\])?/g, "")
    .replace(/[{}]/g, "").replace(/\s+/gu," ").trim();
  const context=plain(String(pdfContext||"")).replace(/^\d+(?:\.\d+)*\s+/,"")
    .replace(/\s+\S+-$/, "").trim(); // Ignore a hyphenated word cut off by the PDF line.
  if(originLine){
    const local=matches(base,needle,originLine.start,Math.min(base.length,originLine.end+needle.length))
      .filter(m=>m.start<=originLine.end);
    const sourceContext=plain(originLine.text);
    const verified=context.length>=12 && context!==needle &&
      (sourceContext.includes(context)||context.includes(sourceContext)) && sourceContext.length>=12;
    // Long phrases can prove themselves on the mapped line when PDF context is
    // unavailable. Never promote a short, repeated word by line-distance ranking.
    const selfVerified=!context && needle.length>=12;
    if(local.length===1 && (verified||selfVerified))origin=local[0];
  }
  const oldParsed = model.parseProposals(filename, base);
  // SyncTeX attributes all of a proposal's text to the wrapper's closing line, so a
  // line inside a wrapper names only the proposal: refine within its shown text.
  const wrapper=!origin&&originLine&&oldParsed.proposals.find(p=>p.format==="tex"&&
    originLine.start<p.end&&originLine.end>=p.start&&p.replacementEnd>p.replacementStart);
  if(wrapper){
    const inside=matches(base,needle,wrapper.replacementStart,wrapper.replacementEnd);
    if(inside.length===1)origin=inside[0];
  }
  const exactOrigin=!!origin;
  if(!origin&&wrapper){
    const raw=base.slice(wrapper.replacementStart,wrapper.replacementEnd);
    const start=wrapper.replacementStart+raw.length-raw.trimStart().length;
    origin={start,end:start+raw.trim().length};
  }
  if(!origin){
    if(!originLine)return unresolved();
    origin={start:originLine.start,end:originLine.end};
  }
  const answer=(range,exact=false,unchangedLine=false)=>({
    status:exact?"exact":base===current||unchangedLine?"line":"changed",
    selectionStart:range.start,selectionEnd:exact?range.end:range.start,
    message:exact?"":base===current||unchangedLine?"Opened the source line corresponding to the PDF selection.":"This passage has changed since the PDF was built.",
  });
  if(base===current)return answer(origin,exactOrigin);
  const oldMatches=matches(base,needle);
  const newParsed = model.parseProposals(filename, current);
  const normalized=s=>s.trim().replace(/\s+/gu," ");
  const paragraph=(text,offset)=>{
    const breaks=[...text.matchAll(/\n[\t ]*\n/g)];
    const before=breaks.filter(m=>m.index+m[0].length<=offset).at(-1);
    const after=breaks.find(m=>m.index>=offset);
    return {start:before?before.index+before[0].length:0,end:after?after.index:text.length};
  };
  const trimmed=(text,range)=>{
    const raw=text.slice(range.start,range.end),leading=raw.length-raw.trimStart().length;
    return {start:range.start+leading,end:range.start+raw.trimEnd().length};
  };
  // Stable proposal IDs retain the role: the original and proposed versions are
  // different passages, even when they happen to contain the same phrase.
  const scopes=(text,parsed)=>{
    const result=[];
    for(const p of parsed.proposals){
      // A Markdown original lives in the encoded header, so only its replacement is a passage.
      if(p.format==="tex")result.push({key:"proposal:"+p.id+":2",start:p.originalStart,end:p.originalEnd});
      result.push({key:"proposal:"+p.id+":3",start:p.replacementStart,end:p.replacementEnd});
    }
    // Review-card IDs survive prose rewrites. A missing closing marker (e.g. a
    // partially edited draft) is bounded by the paragraph, never the whole file.
    const starts=/\\ReviewCardMaskStart\s*\{([^{}]+)\}\s*\{([^{}]+)\}[\t ]*(?:%[^\n]*)?\n?/g;
    for(const m of text.matchAll(starts)){
      const lineStart=text.lastIndexOf("\n",m.index-1)+1;
      if(text.slice(lineStart,m.index).includes("%"))continue;
      const start=m.index+m[0].length, block=paragraph(text,start);
      const escaped=s=>s.replace(/[.*+?^${}()|[\]\\]/g,"\\$&");
      const close=new RegExp("\\\\ReviewCardMaskEnd\\s*\\{"+escaped(m[1])+"\\}\\s*\\{"+escaped(m[2])+"\\}").exec(text.slice(start));
      const end=close?Math.min(start+close.index,block.end):block.end;
      result.push({key:"review:"+m[1]+":"+m[2],start,end});
    }
    return result;
  };
  const oldScopes=scopes(base,oldParsed), newScopes=scopes(current,newParsed);
  const containing=oldScopes.filter(s=>origin.start>=s.start&&origin.end<=s.end).sort((a,b)=>(a.end-a.start)-(b.end-b.start));
  for(const oldScope of containing){
    if(oldScopes.filter(s=>s.key===oldScope.key).length!==1)return unresolved();
    const candidates=newScopes.filter(s=>s.key===oldScope.key);
    if(candidates.length>1)return unresolved();
    if(!candidates.length){
      if(oldScope.key.startsWith("review:"))return unresolved();
      // An accepted proposal leaves its replacement unwrapped in the source.
      const shown=oldScope.key.endsWith(":3")?normalized(base.slice(oldScope.start,oldScope.end)):"";
      const unwrapped=shown.length>=12?matches(current,shown):[];
      if(unwrapped.length===1){
        const found=exactOrigin?matches(current,needle,unwrapped[0].start,unwrapped[0].end):[];
        if(found.length===1)return answer(found[0],true);
        if(found.length>1)return unresolved();
        return answer(unwrapped[0],false,true);
      }
      continue; // Rejected, or edited after the accept.
    }
    const next=candidates[0], found=exactOrigin?matches(current,needle,next.start,next.end):[];
    if(found.length===1)return answer(found[0],true);
    if(found.length>1)return unresolved();
    if(!current.slice(next.start,next.end).trim())return unresolved();
    // A rewritten stable region identifies a passage, never an exact word match.
    return answer(trimmed(current,next));
  }
  const block=paragraph(base,origin.start), oldBlock=base.slice(block.start,block.end);
  const blocks=matches(current,normalized(oldBlock));
  if(blocks.length===1&&matches(base,normalized(oldBlock)).length===1){
    const found=exactOrigin?matches(current,needle,blocks[0].start,blocks[0].end):[];
    if(found.length===1)return answer(found[0],true);
    const sameLine=matches(current,normalized(base.slice(origin.start,origin.end)),blocks[0].start,blocks[0].end);
    if(!exactOrigin)return sameLine.length===1?answer(sameLine[0],false,true):answer(blocks[0],false,true);
  }
  // Translate an unchanged line using unique line anchors (a bounded, linear
  // line comparison). Duplicate lines are not paired by their old line number.
  const uniqueRows=rows=>{
    const map=new Map();for(const row of rows){const key=row.text.trim();if(key.length<8)continue;map.set(key,map.has(key)?null:row);}return map;
  };
  const oldUnique=uniqueRows(oldLines),newUnique=uniqueRows(newLines);
  const anchors=[];
  for(const [key,row] of oldUnique){const next=newUnique.get(key);if(row&&next)anchors.push({old:row,next});}
  const same=anchors.find(a=>origin.start>=a.old.start&&origin.end<=a.old.end);
  if(same){
    const found=exactOrigin?matches(current,needle,same.next.start,same.next.end):[];
    return found.length===1?answer(found[0],true):answer(same.next,false,true);
  }
  // Exact text can relocate a moved passage only when unique in BOTH versions.
  const newMatches=exactOrigin?matches(current,needle):[];
  if(needle.length>=12&&oldMatches.length===1&&newMatches.length===1)return answer(newMatches[0],true);
  // A replacement hunk between unchanged boundaries can identify one rewritten
  // paragraph. Larger edits, deletion, reordered anchors and ties stay unresolved.
  const before=anchors.filter(a=>a.old.end<=block.start).at(-1);
  const after=anchors.find(a=>a.old.start>=block.end);
  if(before&&after&&before.next.end<after.next.start){
    const oldGap=base.slice(before.old.end,after.old.start).trim();
    const range=trimmed(current,{start:before.next.end,end:after.next.start});
    const nextGap=current.slice(range.start,range.end);
    if(normalized(oldGap)===normalized(oldBlock)&&nextGap&&
       !/\n[\t ]*\n/.test(nextGap)&&!anchors.some(a=>a.old.start>before.old.start&&a.old.start<after.old.start))return answer(range);
  }
  return unresolved();
}
if(typeof module!=="undefined")module.exports={resolveSourceNavigation};
