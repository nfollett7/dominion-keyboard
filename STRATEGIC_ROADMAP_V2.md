# Dominion Keyboard: Strategic Roadmap V2 (2026-2030)

**Date:** May 5, 2026  
**Author:** Manus AI  
**Domain:** AI Architecture / Mobile Input Methods  
**Vision:** The transitional bridge from typing to agentic delegation  

---

## Executive Summary

The V1 roadmap (May 4, 2026) correctly identified the shift from command-based interaction to intent-based outcome specification. However, it underestimated the velocity of the market. As of April 2026, major players like OpenAI are actively developing agent-first smartphones that eliminate the app grid entirely [1]. Concurrently, protocols like the Model Context Protocol (MCP) and Agent-to-Agent (A2A) communication have reached production readiness [2]. 

The era of typing is not dissolving in "3-5 years"—it is dissolving *now*. 

Dominion Keyboard V2 pivots from building a "better GBoard" to building the **last text-entry interface you will ever need**. It is designed to be the transitional bridge: a keyboard that makes itself obsolete by understanding user intent so perfectly that typing becomes a fallback rather than the primary mode of interaction. This roadmap aligns with the philosophy of "living from alignment" by offloading cognitive burden to an autonomous, federated agent mesh.

---

## 1. The Current State: Where We Are (May 2026)

Following the rigorous production audits and integration passes of Phase A, B, and C, the Dominion Keyboard architecture is now fundamentally sound. We are no longer building a prototype; we are operating on a production-grade foundation.

### What We Have Built:
- **Canvas Rendering Architecture:** Zero view-hierarchy rendering matching GBoard's performance profile.
- **GBoard-Equivalent Touch Model:** ACTION_UP commit, expanded touch targets, and probabilistic key detection.
- **Input Intelligence System:** Full message capture (not keystroke logging) with app context, temporal tracking, and strict privacy boundaries (password fields blocked).
- **Hybrid Autocorrect:** Local edit-distance matching for instant corrections, paired with GPT-4o-mini context-aware correction on message send.
- **Agentic Foundation:** `AgentRouter` and intent detection logic are built into the OpenAI client.

### The Pivot from V1:
V1 assumed we needed to match GBoard feature-for-feature (stickers, GIFs, one-handed mode) before moving to agentic work. V2 recognizes that building a perfect typing experience is a trap. We must achieve *acceptable* typing (which we have) and immediately pivot to *exceptional* delegation.

---

## 2. The 2030 Agentic Architecture: The Mesh

The smartphone of 2030 is an orchestration node, not a grid of applications [1]. Dominion Keyboard must serve as the omnipresent gateway to this node.

### 2.1 The Federated Gateway (MCP + A2A)
The keyboard will integrate a native Model Context Protocol (MCP) client. When a user types or speaks an intent (e.g., "Schedule a meeting with Sarah tomorrow"), the keyboard does not just output text. It translates the intent into a structured JSON payload and routes it via A2A protocols to specialized backend agents (e.g., a Google Calendar MCP server or a Notion MCP server) [2] [3].

| Component | V1 Concept | V2 Reality (Current Tech) |
| :--- | :--- | :--- |
| **Tool Connection** | Direct API calls | MCP (Model Context Protocol) |
| **Agent Delegation** | Monolithic LLM | A2A (Agent-to-Agent Protocol) |
| **Enterprise Routing** | Webhooks | ACP (Agent Communication Protocol) |

### 2.2 The Digital Twin Memory Layer
The Input Intelligence System we built in Phase C is the data ingestion engine for the digital twin. It captures full messages, context, and temporal patterns. 

In V2, this data will be synchronized to a **Notion-powered Memory UI Dashboard**. Notion acts as the canonical source of truth for the user's vocabulary, relationships, scheduling constraints, and life goals. Before the keyboard's A2A router delegates a task, it queries this Notion memory store to ensure the action aligns with the user's constraints [4].

### 2.3 Proactive Context Awareness
The keyboard must move from reactive to proactive. By leveraging Android Accessibility APIs, the keyboard will "read" the screen context. If the user is viewing a flight confirmation email, the keyboard should proactively surface an intent chip ("Add to Calendar" or "Book Uber") before the user taps the text field.

---

## 3. Implementation Roadmap: The Path to 2030

To achieve this vision, we must execute the following phases, prioritizing agentic integration over legacy typing features.

### Phase 1: The Memory Bridge (Months 1-3)
*Shift from local storage to persistent, editable digital twin.*
- Integrate the Notion MCP server to synchronize the local `InputCapture` database with the user's Notion workspace.
- Develop the **Memory UI Dashboard** in Notion, allowing the user to inspect, edit, and prune their digital twin data.
- Implement federated data pruning: keep only aggregated personalization weights locally, pushing raw context to the secure Notion vault.

### Phase 2: The Agentic Gateway (Months 3-6)
*Shift from text output to intent routing.*
- Integrate a lightweight MCP client directly into the keyboard service.
- Replace the suggestion bar with an **Intent Bar**. When `AgentRouter` detects an actionable intent, surface it as an executable chip (e.g., [📅 Schedule] or [💳 Pay]).
- Connect the keyboard to external MCP servers (e.g., Zapier, Google Calendar) to execute actions directly from the input field.

### Phase 3: Proactive Orchestration (Months 6-12)
*Shift from reactive typing to proactive delegation.*
- Implement Android Accessibility Services to provide the agent router with real-time screen context.
- Develop "Zero-Tap" workflows: the keyboard anticipates the required response or action based on the digital twin and screen context, requiring only user confirmation.
- Transition the primary input modality from typing to voice dictation, utilizing the 5-minute Whisper timeout architecture established in the production audit.

### Phase 4: The Post-Keyboard Era (2028-2030)
*The keyboard makes itself obsolete.*
- As operating systems transition to agent-first interfaces [1], Dominion evolves from a soft-keyboard into a persistent, multi-modal overlay.
- Full integration with the "living from alignment" philosophy: the system acts as a protective layer, managing incoming requests and outgoing tasks autonomously, perfectly aligned with the user's Notion-defined goals.

---

## References

[1] TechCrunch, "OpenAI could be making a phone with AI agents replacing apps," *techcrunch.com*, Apr. 2026.  
[2] HackerNoon, "MCP, A2A, AGP, ACP: Making Sense of the New AI Protocols," *hackernoon.com*, May 2025.  
[3] GitHub, "nborwankar/awesome-mcp-servers-2," *github.com*, Aug. 2025.  
[4] IEEE Journal on Selected Areas in Communications, "From large ai models to agentic ai: A tutorial on future intelligent communications," *ieeexplore.ieee.org*, 2026.
