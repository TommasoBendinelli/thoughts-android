// Written for Thoughts. License is GPLv3.

const {test}=require('node:test'), assert=require('node:assert/strict');
const {resolveSourceNavigation:resolve}=require('../app/src/main/assets/proposal-review/sourceNavigation.js');
const review=(id,text)=>`\\ReviewCardMaskStart{${id}}{0}%\n${text}\n\\ReviewCardMaskEnd{${id}}{0}%\n`;
const proposal=(id,old,next)=>`\\agentProposedEdit[created=2026-09-14T10:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{${id}}{Reason.}{${old}}{${next}}`;
const selected=(source,result)=>source.slice(result.selectionStart,result.selectionEnd);
test('same revision and whitespace selection',()=>{
 const source='Header\nA selected\npassage here.';
 const r=resolve(source,source,'selected passage',2);
 assert.equal(r.status,'exact');assert.equal(selected(source,r),'selected\npassage');
});
test('inserted lines relocate exact text without using old line in the draft',()=>{
 const old='Heading\n\nA target passage.\n\nFooter';
 const now='Inserted introduction.\n'.repeat(80)+old;
 const r=resolve(old,now,'target passage',3);
 assert.equal(r.status,'exact');assert.equal(selected(now,r),'target passage');
});
test('review ID identifies rewritten passage, even with a missing end marker in draft',()=>{
 const old='Header\n\n'+review('card-id','The corrected experiment trains both arms.');
 const now='Different introduction.\n\n\\ReviewCardMaskStart{card-id}{0}%\nWe compare two models.\nOnly their training loss differs.\n\nFollowing paragraph.';
 const r=resolve(old,now,'corrected experiment',4);
 assert.equal(r.status,'changed');assert.equal(r.selectionStart,now.indexOf('We compare two models.\nOnly their training loss differs.'));assert.equal(r.selectionEnd,r.selectionStart);
 assert.match(r.message,/changed since the PDF/);
});
test('duplicate phrase is resolved by original PDF line and stable identity',()=>{
 const old=review('a','Repeated phrase here.')+'\n'+review('b','Repeated phrase here.');
 const now=review('b','Repeated phrase here.')+'\n'+review('a','Rewritten first region.');
 const r=resolve(old,now,'Repeated phrase',2);
 assert.equal(r.status,'changed');assert.equal(r.selectionStart,now.indexOf('Rewritten first region.'));assert.equal(r.selectionEnd,r.selectionStart);
});
test('deleting a stable region never jumps to another occurrence',()=>{
 const old=review('a','Repeated phrase here.')+'\n'+review('b','Repeated phrase here.');
 const r=resolve(old,review('b','Repeated phrase here.'),'Repeated phrase',2);
 assert.equal(r.status,'unresolved');assert.equal(r.selectionStart,undefined);
});
test('ambiguous stable identity is unresolved',()=>{
 const old=review('a','Target phrase here.');
 assert.equal(resolve(old,review('a','One.')+review('a','Two.'),'Target phrase',2).status,'unresolved');
});
test('proposal role is preserved through rewrite',()=>{
 const old=proposal('p-id','Old wording.','Target phrase.');
 const now=proposal('p-id','Target phrase.','New proposed wording.');
 const r=resolve(old,now,'Target phrase',1);
 assert.equal(r.status,'changed');assert.equal(r.selectionStart,now.indexOf('New proposed wording.'));assert.equal(r.selectionEnd,r.selectionStart);
});
test('accepted proposal can be followed to unwrapped unique text',()=>{
 const old=proposal('p-id','Old wording.','Target phrase.');
 const r=resolve(old,'Target phrase.','Target phrase',1);
 assert.equal(r.status,'exact');
});
test('single replacement paragraph between unchanged boundaries',()=>{
 const old='Unique preceding boundary.\n\nOriginal target wording.\n\nUnique following boundary.';
 const now=old.replace('Original target wording.','Entirely rewritten passage.');
 const r=resolve(old,now,'target wording',3);
 assert.equal(r.status,'changed');assert.equal(r.selectionStart,now.indexOf('Entirely rewritten passage.'));assert.equal(r.selectionEnd,r.selectionStart);
});
test('deleted paragraph is unresolved',()=>{
 const old='Unique preceding boundary.\n\nOriginal target wording.\n\nUnique following boundary.';
 assert.equal(resolve(old,old.replace('Original target wording.',''),'target wording',3).status,'unresolved');
});
test('multiple replacement paragraphs are not guessed',()=>{
 const old='Unique preceding boundary.\n\nOriginal target wording.\n\nUnique following boundary.';
 const now=old.replace('Original target wording.','First replacement.\n\nSecond replacement.');
 assert.equal(resolve(old,now,'target wording',3).status,'unresolved');
});
test('duplicate plain paragraphs do not become a false match after deletion',()=>{
 const old='Same target paragraph.\n\nSame target paragraph.';
 assert.equal(resolve(old,'Same target paragraph.','target paragraph',3).status,'unresolved');
});
test('missing build evidence never treats current text as the old revision',()=>{
 const r=resolve(null,'Target phrase.','Target phrase',1);
 assert.equal(r.status,'unresolved');assert.match(r.message,/source version.*unavailable/);
});
test('macro selection uses safe mapped line and translates it through inserted lines',()=>{
 const old='Header line.\nA \\textbf{bold} phrase.\nFooter line.';
 const now='Extra introduction.\n'+old;
 const r=resolve(old,now,'A bold phrase.',2);
 assert.equal(r.status,'line');assert.equal(r.selectionStart,now.indexOf('A \\textbf{bold} phrase.'));assert.equal(r.selectionEnd,r.selectionStart);
});
test('ambiguous original selection and invalid mapped line remain unresolved',()=>{
 assert.equal(resolve('Target here.\nTarget here.','Target here.\nChanged.','Target',99).status,'unresolved');
});

test('accuracy in a heading never selects a distant repeated word',()=>{
 const heading='\\subsection{Diagnosis-only baseline: accuracy and absolute tool counts}';
 const source=heading+'\n'+('Padding line.\n'.repeat(30))+'Report accuracy, measurement quality separately.';
 const context='8.4 Diagnosis-only baseline: accuracy and absolute tool counts';
 const right=resolve(source,source,'accuracy',1,context);
 assert.equal(right.status,'exact');assert.equal(right.selectionStart,source.indexOf('accuracy'));
 const wrong=resolve(source,source,'accuracy',32,context);
 assert.equal(wrong.status,'line');assert.equal(wrong.selectionEnd,wrong.selectionStart);
 const shifted='New introduction.\n'+source;
 const moved=resolve(source,shifted,'accuracy',1,context);
 assert.equal(moved.selectionStart,shifted.indexOf('accuracy'));assert.equal(moved.status,'exact');
});
test('short words without surrounding text use a caret',()=>{
 const source='Report accuracy here.\nOther accuracy there.';
 const r=resolve(source,source,'accuracy',1);
 assert.equal(r.status,'line');assert.equal(r.selectionStart,0);assert.equal(r.selectionEnd,0);
});
test('even a unique selected phrase cannot override a conflicting mapped line',()=>{
 const source='Unrelated heading.\nA unique selected passage follows.';
 const r=resolve(source,source,'unique selected passage',1);
 assert.equal(r.status,'line');assert.equal(r.selectionEnd,0);
});

test('PDF heading context may end with a hyphenated line break',()=>{
 const source='\\section{Diagnosis-only baseline: accuracy and absolute tool counts}';
 const r=resolve(source,source,'accuracy',1,'1 Diagnosis-only baseline: accuracy and abso-');
 assert.equal(r.status,'exact');assert.equal(selected(source,r),'accuracy');
});
// SyncTeX maps a proposal's whole text to the wrapper's closing line.
const multiline=(id,old,next)=>`\\agentProposedEdit[created=2026-10-16T07:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{${id}}{Reason.\n}{${old}}{\n${next}\n}`;
const accepted=()=>{
 const shown='The scores are the average log-probability of each model.\nHere, the reference response is privileged information.';
 const wrapper=multiline('p-id','The scores are averages.',shown);
 const base='Unique heading line.\n'+wrapper+'\n\nUnique following line.';
 return {base,current:'Unique heading line.\n'+shown+'\n\nUnique following line.',closing:base.split('\n').indexOf('}')+1};
};
test('accepted proposal: a phrase mapped to the closing line is found in the unwrapped text',()=>{
 const {base,current,closing}=accepted();
 for(const phrase of ['reference response is privileged','The scores are','log-probability']){
  const r=resolve(base,current,phrase,closing,'');
  assert.equal(r.status,'exact',phrase);assert.equal(selected(current,r),phrase);
 }
});
test('pending proposal: a phrase mapped to the closing line is found in the shown text',()=>{
 const {base,closing}=accepted();
 const r=resolve(base,base,'The scores are',closing,'');
 assert.equal(r.status,'exact');assert.equal(r.selectionStart,base.indexOf('The scores are the average'));
});
test('accepted proposal edited afterwards is not guessed from the old text',()=>{
 const {base,current,closing}=accepted();
 const edited=current.replace('average log-probability','mean log-probability');
 const r=resolve(base,edited,'average log-probability',closing,'');
 assert.notEqual(r.status,'exact');
});
