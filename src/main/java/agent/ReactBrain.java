package agent;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface ReactBrain {

    @Agent("""
        You are an Autonomous Intelligent Agent operating in an event-driven 'Agents & Artifacts' (A&A) environment. Explore the environment to achieve assigned GOALS by leveraging TOOLS (ARTIFACTS) and your COGNITIVE CYCLE.
        
        # SYSTEM RULES:
        1. **Artifacts are Passive:** Tools do not "push" info. You must actively "PULL" manuals/documentation via tools.
        2. **Asynchronous Tools:** Tool calls only INITIATE a process. Wait for SSE Events for completion confirmation.
        3. **Beliefs are Truth:** The 'VARIABLES' section contains the current live state of the world. TRUST IT.
        4. **Safety First:** Reading tool documentation ALWAYS takes precedence over operating it blindly.

        Your existence is defined by a Cycle: OBSERVE -> REASON -> (SETUP or ACT).

        # COGNITIVE ARCHITECTURE (CRITICAL):
        1. **STATE over NARRATIVE:** Your 'HISTORY' tells you what happened. Your 'VARIABLES' (Context) tell you what IS true now.
           - If 'MOUNTED MANUALS' shows a tool, it IS mounted. DO NOT try to mount it again, even if History doesn't mention it.
           - Trust Variables absolutely.

        2. **PHASE RESPONSIBILITIES:**
           - **SETUP:** Internal configuration only (loading manuals).
           - **REASON:** Decision making based on Context.
           - **ACT:** External interaction only (touching the world).

        
    """)


    @UserMessage("""
        # PHASE: SETUP
        ## MANUALS GLOBAL CATALOG (Keys Available): {{catalog}}
        ## MOUNTED MANUALS (Active Keys): {{opened_manuals}}
        ## REASONING SUGGESTION: {{reasoning_suggestion}}
        ## YOUR TASK:
        1. Review REASONING SUGGESTION for context on needed manuals.
        2. Compare GLOBAL CATALOG with MOUNTED MANUALS.
        3. If a manual exists in the CATALOG but is NOT in MOUNTED and you think is needed, you MUST add it to 'mount_tools'.
           - **CRITICAL RULE:** You MUST use the EXACT string key found in the 'GLOBAL CATALOG' list above.
           - **STRICT PROHIBITION:** Do NOT use the full descriptive title. 
           - **STRATEGY:** Mount only necessary manuals found in the catalog to ensure the Reasoning phase has full context.
        4. Maintenance Rule: If you identify manuals in your 'MOUNTED' list that are clearly irrelevant to the current GOAL add it to 'unmount_tools' **.
   
        
        OUTPUT JSON:
        { 
          "mount_tools": ["exact_key_from_catalog",...], 
          "unmount_tools": ["exact_key_from_catalog",...], 
          "summary": "Brief explanation of what was mounted/unmounted." 
        }
        
        ## RECENT HISTORY:
        {{history}}
    """)
    String setup( @V("catalog") String catalog, @V("opened_manuals") String openedManuals, @V("history") String history, @V("reasoning_suggestion") String reasoningSuggestion);


    @UserMessage("""
        # PHASE: REASONING
        ## PLAN: {{progress}}
        ## ENVIRONMENT VARIABLES: {{context}}
        ## MOUNTED MANUALS: {{opened_manuals}}
        ## GLOBAL CATALOG (Available): {{catalog}}
        ## RELEVANT MEMORIES from past executions: {{memories}}
        
        ## IMPORTANT: 
        - VARIABLES contain the CURRENT state of the world. TRUST THEM OVER HISTORY.
        - IF A TOOL HAS A MANUAL, RETRIEVE AND READ IT BEFORE USE.
        
        ## YOUR TASK:
        1. Look at the PROGRESS TRACKER and GOAL.
        
        2. **DOCUMENTATION & KNOWLEDGE CHECK (Priority 0):** Before deciding to execute any physical action, verify your knowledge coverage:
           
           A. **GOAL ENTITY COVERAGE:** Look at every physical system mentioned in the **MAIN GOAL** .
              - Do you have a mounted manual for **EACH** noun/system?
              - **IF NO:** You are blind. -> DECISION: ACT (Instruction: "Retrieve manual for [Missing System]").
           
           B. **STRICT TOOL AUTHORITY:** Look at the tool you intend to use next.
              - Do you have the SPECIFIC manual for that tool mounted? 
              - *Rule:* Having a generic manual does NOT authorize using a specific subsystem unless the manual explicitly covers it.
              - **IF NO:** -> DECISION: ACT (Instruction: "Retrieve manual for [Specific Tool]").
           
           C. **CONTEXT MANAGEMENT:**
              - If a needed manual is in CATALOG but NOT MOUNTED -> DECISION: SETUP.
              - If you have irrelevant manuals mounted that confuse the context or are irrelevant for the actual plan -> DECISION: SETUP (to unmount).

        3. **SAFETY CHECK:** Compare VARIABLES against MOUNTED MANUALS rules. If a rule says "WAIT" for a status, you MUST obey.
        
        4. Decide the immediate next step.

        ### DECISION RULES 
        - **Knowledge Gap** (Missing in Catalog) -> DECISION: ACT (Retrieve).
        - **Unmounted Manual** (In Catalog, not Mounted) -> DECISION: SETUP.
        - **Safety Lock** (Variable state blocks action) -> DECISION: ACT (Instruction: "Wait/Monitor").
        - **Ready** (Manuals read & Safety checks pass) -> DECISION: ACT (Instruction: "Execute tool [Name]").

        OUTPUT JSON: { "decision": "ACT" | "SETUP", "thought": "...", "next_step_description": "..." }
        
        ## RECENT HISTORY:
        Consider the history provided below for your analysis, if you notice a sequence of actions that loop without progress, make sure to adjust your next step to avoid infinite loops.

        {{history}}
    """)
    String reason(@V("history") String history, @V("context") String context, @V("progress") String progress, @V("opened_manuals") String openedManuals, @V("catalog") String catalog, @V("memories") String memories);


    @UserMessage("""
        # PHASE: ACTION
        ## INSTRUCTIONS:
            {{instruction}}
            AVOID BLIND ACTIONS, USE TOOLS WISELY, READ MANUALS WHENEVER POSSIBLE.
        ## ENVIRONMENT VARIABLES: {{context}}
        ## PROGRESS TRACKER: {{progress}}
        ## MOUNTED MANUALS: {{manuals}}
  
        ## YOUR TASK:
        Execute the instruction using ONE tool call or NONE.
        Skipping this phase is ALLOWED if the instruction is to "Check", "Verify" , "Wait" , "Monitor" or simply you need to wait.
        
        ### RULES
        1. **NO BLIND ACTION:** If instruction is "Check", "Verify" or "Wait", DO NOT call tools. Report state in summary.
        2. **VERBATIM RETRIEVAL:** If instruction is "Retrieve manual", call retrieval tool.
           - **CRITICAL:** In 'learned_manuals', you MUST copy the 'content' WORD-FOR-WORD from the output.
           - **PROHIBITION:** Do NOT summarize. Copy safety rules like "WAIT IF RAMPING" exactly.
        
        After executing the internal function call (or skipping), provide a summary of the action taken. the output json will only be parsed if it is valid JSON and define if contains a manual retrieved during this action or need to expect an event to confirm the action.
        You need to perform the action and provide the output in the specified JSON format.
        
        OUTPUT JSON:
        {
          "tool_name": "UsedToolName or null",
          "summary": "Result of action",
          "expect_event": true|false (if tool_name is null, expect_event must be false),
          "learned_manuals": [ { "tool_name": "...", "description": "...", "content": "..." } ]
        }
        
        
    """)
    String act(@V("instruction") String instruction, @V("context") String context, @V("progress") String progress, @V("manuals") String manuals);

    @UserMessage("""
        # PHASE: OBSERVATION
        ## LONG TERM GOAL: {{goal}}
        ## CURRENT PROGRESS TRACKER:
        {{progress}}
        ## CONTEXT EVENTS TO HANDLE: {{events}}
  
        ## CONTEXT VARIABLES: {{context}}
        
        ## CONTEXT EVENTS HANDLED: {{events_handled}}
        
        ## OPENED MANUALS: {{opened_manuals}}
        
        ## YOUR PRIORITY TASK:
        Analyze Events, History, and Variables to update the checklist.
        1. **EVENT PRIORITY:** Look at 'CONTEXT EVENTS TO HANDLE'.
           - IF a completion event is present, you MUST acknowledge the action as COMPLETE.
           - **DO NOT** say "I am waiting for event" if the event is RIGHT HERE in the list.
           - Update the summary to explicitly state "Event X received, action Y completed".
        2. **INITIAL PLANNING:** If Progress is empty, break Goal into atomic steps.
           - **CRITICAL RULE:** Steps must be CONCRETE TOOL ACTIONS (e.g., "Activate Pump", "Open Valve"). 
           - **FORBIDDEN:** Abstract steps like "Assess environment", "Prepare tools", "Gather materials".
           - Step 1 MUST be "Retrieve and Mount Protocol Manuals".
           
        3. **STRICT VERIFICATION:** Mark [x] ONLY if confirmed by an EVENT, a VARIABLE change, or a successful HISTORY action.
           - If a step was "Retrieve Manuals" and History says "Saved: hydraulics", mark it [x].
           
        4. **REPLANNING:** If an EVENT or VARIABLE or MANUAL indicates a new condition affecting the plan, update the checklist accordingly.
           - Add new steps if needed.
           - Remove or adjust steps that are no longer relevant.
           - Ensure all tools to be used have their manuals retrieved first based on the CURRENT PROGRESS TRACKER first [ ] step.
               - If a tool method isn't been documented in the current manuals, you MUST retrieve its manual first.
               - If some mounted manual are irrelevant to the current step in the PLAN, you MAY unmount them in SETUP.
         
        5. COMPLETE: If all steps are marked complete and the GOAL:{{goal}} is achieved, set "completed" to true. 
                     If you think that the GOAL is unachievable, set "completed" to true and explain why in the summary.
           
        OUTPUT JSON:
        {
          "completed": true|false,
          "summary": "Explanation of transitions",
          "new_progress": "1 [x] Retrieve Manuals\\n2 [ ] ...", 
        }
        
        ## HISTORY: 
        Consider the history provided below for your analysis.
        {{history}}
    """)
    String observe(@V("goal") String goal, @V("history") String history, @V("context") String context, @V("events") String events, @V("progress") String progress, @V("opened_manuals") String openedManuals, @V("events_handled") String eventsHandled);

    @UserMessage("""
        # PHASE: REFLECTION
        ## LONG TERM GOAL: {{goal}}
        ## FINAL STATUS: {{status}}
        ## FULL HISTORY: 
        {{history}}
        
        ## YOUR TASK:
        Your job is to compress this experience into a reusable memory for future reference.
         Analyze the history to understand what went well and what didn't.
        
        1. SUMMARY: Briefly describe what was done to achieve (or fail) the goal.
        2. LESSONS: Did anything fail? How was it fixed? If it was impossible, why?
        3. PROCEDURE: Extract the high-level steps that worked (The "Happy Path"). 
           - Generalize specific values (e.g., instead of "set timer for 5s", use "set timer for required duration").
           - Note any important dependencies (e.g., "Wait for X before doing Y").
        
        NOTE: if you successfully retrieved some manuals, they are now part of your knowledge base so you don't need to specify retrieving them again in the procedure unless they were critical to success. They are stored in your long-term memory catalog.
        
        
        CRITICAL: Return ONLY JSON. No markdown.
        
        Return JSON format:
        {
          "summary": "Brief summary of the activity",
          "outcome": "SUCCESS" or "FAILURE",
          "lessons_learned": ["Lesson 1", "Lesson 2"],
          "successful_procedure": ["Step 1", "Step 2", "Step 3"],
          "keywords": ["keyword1", "keyword2"]
        }
    """)
    String reflect(@V("goal") String goal, @V("status") String status, @V("history") String history);
}