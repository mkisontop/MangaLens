"""Compare the actual correction replies of the pinned models on authored, unrelated lines."""
import json
import os
import urllib.request


combined = ("Read each original again with its preceding dialogue. For listener_request, the standalone Japanese negative "
    "request addresses the listener. For actor_and_owner, Korean 내 or 제 identifies the owner of a noun. "
    "Determine separately who performed the final action. A noticing ending -네 often describes the companion's action. "
    "Follow the preceding exchange and use implicit wording if the actor is uncertain. Preserve the action, all objects, "
    "timing, register and every following clause. Return only JSON with an items array. Each item has its numeric id "
    "and full natural English in en. For actor_and_owner also give actor as speaker, other or unclear.")
japanese = ("Translate each complete Japanese source again. Its opening standalone negative request addresses the listener. "
    "Preserve who is asked to act, the action, timing, register and every following clause. "
    "Return only JSON with an items array. Each item has its numeric id and the full natural English line in en.")
korean = ('Translate the complete Korean source into English using the preceding dialogue. '
    'A possessive such as 내 modifies the following noun. It says who owns the object. '
    'Resolve the separate omitted subject of the final verb from the exchange. With -네, the speaker often notices '
    'what the person just addressed did. Preserve the action and each object. '
    'Return JSON {"items":[{"id":0,"actor":"other","en":"..."}]}. Copy each input id. '
    'Set actor to speaker, other, or unclear according to the source and dialogue. Put only the complete natural '
    'English translation in en. Never copy the draft or schema placeholders as your answer.')
ja = ("まだ箱を開けないで。鍵が届くまで待って。","I'm not opening the box yet. Wait until the key arrives.","")
ko = ("내 가방도 챙겨 왔네.","I brought my bag along, too.","Guide: Ash! Are you taking me to camp?")
panel = ("내 옷이랑, 모자랑, 포션까지 가지고 왔네...", "I brought my clothes, hat, and potions...",
    "— Carrot! You're so cute~ Are you taking me to the competition venue?")
for name, prompt, (src, draft, prior), issue, compact in [
        ("combined-ja",combined,ja,"listener_request",False),("proven-ja",japanese,ja,"listener_request",True),
        ("combined-ko",combined,ko,"actor_and_owner",False),("specialized-ko",korean,ko,"actor_and_owner",True),
        ("specialized-panel",korean,panel,"actor_and_owner",True)]:
    if os.environ.get("MANGALENS_CLASSIFY_ONLY") == "1":
        break
    item = {"id":0,"source":src,"draft":draft}
    if not compact:
        item.update(issue=issue,previous_line=prior)
    elif prior:
        item["previous_line"] = prior
    page = {"review": "japanese_requests" if name=="proven-ja" else "dialogue_roles",
            "story_so_far":[prior] if prior else [],"items":[item]}
    body = {"model":os.environ["MANGALENS_LIVE_MODEL"],"temperature":0,"max_tokens":512,
            "messages":[{"role":"system","content":prompt},{"role":"user","content":"{}\n\n"+
                         json.dumps(page, ensure_ascii=False, separators=(",",":"))}]}
    req = urllib.request.Request(os.environ["MANGALENS_LIVE_ENDPOINT"],data=json.dumps(body).encode(),
                                headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req,timeout=240) as reply:
        response=json.load(reply)
    print("Role probe "+json.dumps({"case":name,"output":response["choices"][0]["message"]["content"],
        "tokens":response["usage"],"timings":response.get("timings",{})},ensure_ascii=False),flush=True)

actor_prompt = ("Decide who physically handled the possessions. The owner and the carrier can be different people. "
    "Use an explicitly marked Korean subject first. For example 내 책은 내가 챙겼네 has actor speaker; "
    "내 책은 지우가 챙겼네 has actor third_person (Jiwoo). 내 or 제 before a noun just means my. "
    "If the earlier speaker calls a helper and asks whether YOU are taking ME somewhere, then notices "
    "내 책도 챙겼네 without an explicit subject, the speaker is observing that helper's kindness: actor listener. "
    "Do not assign that observation to the owner merely because the book is theirs. For each input return its "
    "numeric id and actor: listener, speaker, third_person or unclear. Only for third_person or unclear, also "
    "translate the entire source into en. Keep the actual name and every possession. Output one compact JSON "
    "object, for example {\"items\":[{\"id\":3,\"actor\":\"listener\"},{\"id\":4,\"actor\":\"speaker\"}]}. "
    "No explanations or subsequent conversation.")
for name, src, prior in [("classify-bag",ko[0],ko[2]),("classify-outfit",panel[0],panel[2]),
        ("classify-self","내 가방은 내가 가져왔네.",ko[2]),
        ("classify-third","내 우산은 민지가 가져왔네.",ko[2])]:
    page={"review":"dialogue_roles","story_so_far":[prior],"items":[{"id":0,"source":src,"previous_line":prior}]}
    body={"model":os.environ["MANGALENS_LIVE_MODEL"],"temperature":0,"max_tokens":512,"messages":[
        {"role":"system","content":actor_prompt},{"role":"user","content":"{}\n\n"+json.dumps(page,ensure_ascii=False,separators=(",",":"))}]}
    req=urllib.request.Request(os.environ["MANGALENS_LIVE_ENDPOINT"],data=json.dumps(body).encode(),
                               headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=240) as reply:
        response=json.load(reply)
    print("Role probe "+json.dumps({"case":name,"output":response["choices"][0]["message"]["content"],
        "tokens":response["usage"],"timings":response.get("timings",{})},ensure_ascii=False),flush=True)
