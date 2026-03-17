# JOTP Video Series - Quick Reference Guide

**For:** Video producers, editors, and reviewers
**Purpose:** Quick access to key information during production

---

## Video Series at a Glance

**Total Videos:** 13
**Total Duration:** ~3 hours 28 minutes
**Format:** 1080p (1920x1080) MP4
**Audience:** Java developers (intermediate to advanced)

---

## Video Checklist (Quick Reference)

### Video 01: Introduction to OTP and JOTP (12 min)
- [x] Script written
- [ ] Visual aids created
- [ ] Code examples prepared
- [ ] Recorded
- [ ] Edited
- [ ] Published

**Key Concepts:**
- The concurrency problem
- OTP and "Let It Crash" philosophy
- JOTP vs. Erlang, Akka, Go
- 15 primitives overview
- Performance benchmarks

**Visual Needs:**
- Thread pool exhaustion animation
- Supervisor tree animation
- Performance benchmark charts
- Feature comparison table

---

### Video 02: Your First Process (15 min)
- [x] Script written
- [x] Demo code ready (CounterDemo.java)
- [x] Visual aids designed
- [ ] Recorded
- [ ] Edited
- [ ] Published

**Key Concepts:**
- What is a process?
- Memory footprint (3.9KB)
- State and message definitions
- Proc.spawn() factory
- tell() vs. ask()
- Supervision basics

**Code Demo:**
- `DEMO-CODE/01-counter-demo.java`
- Live coding: Counter process
- Demonstrate crash and restart

**Visual Needs:**
- Process architecture diagram
- Sealed interface pattern matching
- Tell vs. Ask comparison

---

### Video 03: Messaging Patterns (14 min)
- [x] Script outlined
- [x] Demo code ready (PaymentProcessorDemo.java)
- [ ] Visual aids created
- [ ] Recorded
- [ ] Edited
- [ ] Published

**Key Concepts:**
- tell(): fire-and-forget
- ask(): request-reply with timeout
- Backpressure mechanisms
- Multi-process workflows
- Error handling patterns

**Code Demo:**
- `DEMO-CODE/02-payment-processor.java`
- Order processing workflow
- Payment gateway integration
- Audit logging

**Visual Needs:**
- Message sequence diagrams
- Ask timeout animation
- Multi-process workflow

---

### Video 04: Fault Tolerance (15 min)
- [ ] Script written
- [ ] Demo code prepared
- [ ] Visual aids created
- [ ] Recorded
- [ ] Edited
- [ ] Published

**Key Concepts:**
- "Let It Crash" philosophy
- Three restart strategies
- Supervision trees
- Fault isolation
- Restart limits and circuit breaking

**Code Demo:**
- E-commerce supervision tree
- Crash simulation
- Fault isolation demo

**Visual Needs:**
- Supervisor restart animation
- Supervision tree diagram
- Restart strategy comparison
- Circuit breaker state diagram

---

### Video 05: Building a Simple Application (15 min)
- [ ] Script written
- [ ] Demo code prepared
- [ ] Visual aids created
- [ ] Recorded
- [ ] Edited
- [ ] Published

**Key Concepts:**
- Application architecture
- Process-per-user pattern
- Message broadcasting
- State persistence
- Observability

**Code Demo:**
- Chat server application
- Multi-user scenario
- Fault tolerance demo

**Visual Needs:**
- Application architecture diagram
- Process interaction sequence
- Monitoring dashboard mockup

---

## Production Quick Settings

### OBS Studio Configuration
```
Resolution: 1920x1080
Frame Rate: 30 fps
Encoder: x264
Bitrate: 3000 Kbps
Keyframe Interval: 2s
Audio: 48 kHz, 192 Kbps
```

### IDE Configuration
```
Font: JetBrains Mono (18pt)
Theme: Darcula (dark theme)
Zoom: 125%
Line Numbers: Enabled
Code Folding: Disabled
```

### Export Settings (DaVinci Resolve)
```
Format: MP4
Codec: H.264
Resolution: 1920x1080
Frame Rate: 30 fps
Bitrate: 8-10 Mbps
Audio: AAC, 192 Kbps, 48 kHz
```

---

## Visual Aids Quick Reference

### Color Scheme
```
Primary Blue:   #3B82F6
Secondary Green: #10B981
Accent Yellow:  #FBBF24
Warning Orange: #F59E0B
Error Red:      #EF4444
Dark Gray:      #1F2937
Light Gray:     #F9FAFB
White:          #FFFFFF
```

### Typography
```
Headings: Inter/Roboto (32-48pt)
Body: Inter/Roboto (16-18pt)
Code: JetBrains Mono (14-16pt)
Captions: Inter/Roboto (12-14pt)
```

### Animation Timing
```
Fade In/Out: 0.5s
Slide Transitions: 0.3s
Text Reveals: 0.2s per line
Code Typing: Real-time (no speeding up)
Message Flow: 1-2s per animation
```

---

## Code Demo Guidelines

### Before Recording
- [ ] Code compiles without errors
- [ ] All tests pass
- [ ] Examples are simple and focused
- [ ] Comments explain key concepts
- [ ] Code follows JOTP style guide

### During Recording
- [ ] Explain what you're doing
- [ ] Type at moderate pace
- [ ] Use keyboard shortcuts visible
- [ ] Show compiler errors and fixes
- [ ] Run and show output

### Common Issues to Anticipate
- **Typos:** Slow down, double-check
- **Compiler Errors:** Show and explain
- **Long Builds:** Cut in editing
- **Network Issues:** Use local examples
- **IDE Crashes:** Save frequently

---

## Script Format Quick Reference

### Section Structure
```
## Section Title (start time - end time)
**[Visual: Description]**
**Narrator:** Spoken content...

**Code Window:** File name
**[Type code - Description]**
```

### Timing Guidelines
```
Intro: 30-45 seconds
Section: 3-6 minutes each
Code Demo: 5-10 minutes
Summary: 30-60 seconds
Total: 10-20 minutes per video
```

### Key Phrases to Emphasize
- "Let It Crash" philosophy
- Fault containment
- Automatic restart (200 microseconds)
- Message passing over shared state
- 12 million Java developers
- 3.9KB per process
- 4.6M messages/second

---

## Thumbnail Design Templates

### Format
```
Dimensions: 1280x720 (16:9)
File Size: Under 2MB
Format: JPG or PNG
```

### Design Elements
```
Title: Large, bold text (60pt+)
Colors: High contrast
Images: Screenshots, logos, faces
Icons: Simple, recognizable
Layout: Rule of thirds
```

### Title Templates
```
"Video X: [Title] - JOTP Tutorial Series"
"[Title] in JOTP (Java OTP)"
"Build [Concept] with JOTP"
"Why JOTP? [Title]"
```

---

## Metadata Template

### YouTube Description
```markdown
Learn [topic] in JOTP (Java OTP Framework). This video covers [brief description].

📚 Complete Series: [Playlist URL]
💻 Code Examples: [GitHub URL]
📖 Documentation: [Docs URL]

⏱️ Timestamps:
0:00 - Introduction
X:XX - Section 1
X:XX - Section 2
...

🎓 Learning Objectives:
- [Objective 1]
- [Objective 2]
- [Objective 3]

💡 Prerequisites:
- [Prereq 1]
- [Prereq 2]

🔗 Resources:
- [Link 1]
- [Link 2]

#JOTP #Java #Java26 #VirtualThreads #ActorModel
```

### Tags (First 5 - Most Important)
```
1. JOTP
2. Java OTP
3. Java 26
4. Virtual Threads
5. Actor Model
```

---

## Quality Checklist

### Content Quality
- [ ] Factual accuracy verified
- [ ] Code examples tested
- [ ] No typos or errors
- [ ] Clear explanations
- [ ] Appropriate pacing

### Technical Quality
- [ ] Audio clear and understandable
- [ ] Video sharp and steady
- [ ] Code readable (18pt+ font)
- [ ] No glitches or artifacts
- [ ] Captions accurate

### Accessibility
- [ ] Captions/subtitles included
- [ ] High contrast colors
- [ ] Large, readable fonts
- [ ] Audio description (optional)

### Pedagogy
- [ ] Learning objectives clear
- [ ] Examples build on each other
- [ ] Quiz questions test understanding
- [ ] Resources provided

---

## Distribution Checklist

### Pre-Publish
- [ ] Video edited and exported
- [ ] Captions added
- [ ] Thumbnail created
- [ ] Description written
- [ ] Tags selected
- [ ] Timestamps added

### Publish
- [ ] Upload to YouTube
- [ ] Add to playlist
- [ ] Set visibility (Public)
- [ ] Schedule publish time

### Post-Publish
- [ ] Share on Twitter
- [ ] Share on LinkedIn
- [ ] Share on Reddit
- [ ] Announce on GitHub
- [ ] Monitor comments

---

## Engagement Guidelines

### Comment Response Times
```
First 24 hours: Respond to all
First week: Respond to 90%+
Ongoing: Respond to 80%+
```

### Response Templates
```
**Question:** [Copy question]
**Answer:** [Clear, helpful response]
**Resources:** [Link to docs/examples]
**Follow-up:** "Let me know if you need more help!"
```

### Handling Negative Feedback
1. Acknowledge the feedback
2. Thank them for watching
3. Address specific concerns
4. Offer to improve
5. Take conversation to GitHub if needed

---

## Troubleshooting Quick Fixes

### Audio Issues
```
Problem: Background noise
Fix: Noise gate in post-production

Problem: Plosives (P, B sounds)
Fix: Pop filter during recording

Problem: Echo
Fix: Acoustic treatment, closer to mic
```

### Video Issues
```
Problem: Blurry text
Fix: Increase font size to 18pt+

Problem: Screen recording lags
Fix: Lower OBS bitrate, use hardware encoder

Problem: Poor lighting
Fix: Add softbox or LED panel
```

### Engagement Issues
```
Problem: Low views
Fix: Improve thumbnail and title

Problem: Low retention
Fix: Tighten pacing, add visual variety

Problem: No comments
Fix: Ask questions in video, respond to comments
```

---

## Milestone Tracking

### Phase 1: Content Creation (Months 1-3)
- [x] Series outline
- [x] Video 01-02 scripts
- [x] Demo code (Videos 01-02)
- [ ] Video 03-13 scripts
- [ ] Remaining demo code
- [ ] All visual aids

### Phase 2: Recording (Months 4-5)
- [ ] Playlist 1 recorded
- [ ] Playlist 2 recorded

### Phase 3: Post-Production (Month 6)
- [ ] All videos edited
- [ ] Captions added
- [ ] Thumbnails created
- [ ] QA review complete

### Phase 4: Launch (Month 7)
- [ ] All videos published
- [ ] Marketing push complete
- [ ] Community engagement ongoing

---

## Contact Information

**Production Team:**
- Producer: [Name]
- Editor: [Name]
- Reviewer: [Name]

**Community:**
- GitHub: https://github.com/seanchatmangpt/jotp
- Discussions: https://github.com/seanchatmangpt/jotp/discussions
- Email: community@jotp.dev

---

## Quick Links

**Internal Resources:**
- [Series Outline](/docs/video/SERIES-OUTLINE.md)
- [Production Guide](/docs/video/PRODUCTION-GUIDE.md)
- [Demo Code](/docs/video/DEMO-CODE/)
- [Visual Aids](/docs/video/VISUALS/)

**External Resources:**
- [YouTube Channel](https://youtube.com/@jotp) - TODO
- [GitHub Repository](https://github.com/seanchatmangpt/jotp)
- [Documentation](https://jotp.dev) - TODO

---

**Document Status:** Complete
**Last Updated:** 2026-03-16
**Version:** 1.0
**Purpose:** Quick reference during video production

---

Good luck with production! Remember: Content quality matters more than production polish. Start simple, iterate often, engage with your audience! 🎬
