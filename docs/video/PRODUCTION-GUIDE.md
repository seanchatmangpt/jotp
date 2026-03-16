# JOTP Video Tutorial Series - Production Guide

**Version:** 1.0
**Last Updated:** 2026-03-16
**Maintainer:** JOTP Community

---

## Overview

This guide provides comprehensive instructions for producing high-quality video tutorials for the JOTP framework. It covers equipment, recording, editing, publishing, and community engagement.

---

## Table of Contents

1. [Equipment and Setup](#equipment-and-setup)
2. [Recording Environment](#recording-environment)
3. [Recording Guidelines](#recording-guidelines)
4. [Post-Production](#post-production)
5. [Publishing and Distribution](#publishing-and-distribution)
6. [Community Engagement](#community-engagement)
7. [Quality Assurance](#quality-assurance)
8. [Troubleshooting](#troubleshooting)

---

## Equipment and Setup

### Minimum Requirements

**Hardware:**
- Computer: Intel i5/i7/i9 or AMD Ryzen 5/7/9 (last 3 years)
- RAM: 16GB minimum, 32GB recommended
- Storage: 256GB SSD (for video files)
- Microphone: USB condenser microphone (e.g., Blue Yeti, Audio-Technica ATR2500x)

**Software (Free):**
- Screen Recording: OBS Studio (https://obsproject.com/)
- Video Editing: DaVinci Resolve (https://www.blackmagicdesign.com/products/davinciresolve)
- Diagram Creation: draw.io (https://app.diagrams.net/)
- Audio Editing: Audacity (https://www.audacityteam.org/)

**Software (Paid - Optional):**
- Screen Recording: Camtasia ($299)
- Video Editing: Final Cut Pro ($299), Adobe Premiere Pro ($239/year)
- Audio Editing: Adobe Audition ($229/year)

### Recommended Equipment

**For Professional Quality:**

**Microphone:**
- Shure SM7B ($399) + Cloudlifter CL-1 ($149) + Audio Interface ($100-200)
- Or: Rode PodMic ($99) + audio interface
- Pop filter: Essential for plosive sounds

**Lighting:**
- Key light: Softbox or LED panel ($50-150)
- Fill light: Optional but recommended ($30-100)
- Backlight: For depth ($30-80)

**Camera (for face cam):**
- Logitech C920 ($99) or similar
- Or: DSLR/mirrorless camera with capture card

**Audio Interface:**
- Focusrite Scarlett Solo ($109)
- Or: SSL 2 USB ($229)

### Software Configuration

**OBS Studio Settings (Screen Recording):**
- Resolution: 1920x1080 (1080p)
- Frame Rate: 30 fps
- Encoder: x264 or hardware (NVENC/AMF)
- Rate Control: CBR
- Bitrate: 2500-4000 Kbps
- Keyframe Interval: 2s

**OBS Audio Settings:**
- Sample Rate: 48 kHz
- Channels: Stereo
- Bitrate: 192 Kbps
- Audio Track 1: Microphone only

**IDE Configuration:**
- Font: JetBrains Mono or Fira Code (18pt minimum)
- Theme: High contrast (Darcula, Dracula, Nord)
- Line numbers: Enabled
- Code folding: Disabled
- Zoom: 125-150% for readability

---

## Recording Environment

### Ideal Conditions

**Location:**
- Quiet room with minimal echo
- Carpet or rugs to reduce echo
- Away from HVAC noise, refrigerators, traffic
- Consistent temperature (equipment protection)

**Lighting:**
- Natural light (windows) preferred but not direct sunlight
- Three-point lighting setup for face cam
- Soft, diffused lighting (no harsh shadows)
- Avoid overhead lighting (creates raccoon eyes)

**Background:**
- Clean, uncluttered background for face cam
- Solid color wall or bookshelf (professional look)
- Avoid personal items, distracting elements
- Green screen optional (for virtual backgrounds)

### Audio Environment

**Acoustic Treatment (DIY Options):**
- Thick curtains on windows
- Bookshelves filled with books (natural diffusion)
- Acoustic foam panels on walls ($50-200)
- Thick rug on floor
- Reflection filter behind microphone ($30-50)

**Noise Reduction:**
- Close windows and doors
- Turn off fans, AC, appliances
- Use shock mount for microphone
- Pop filter for plosives (P, B sounds)
- Distance: 6-12 inches from microphone

---

## Recording Guidelines

### Pre-Recording Checklist

- [ ] Script reviewed and finalized
- [ ] Code examples tested and working
- [ ] IDE configured for readability
- [ ] Microphone positioned and tested
- [ ] Lighting checked
- [ ] OBS/recording software configured
- [ ] Backup recording method ready
- [ ] Water/tea available (avoid caffeine)
- [ ] Phone silenced or in airplane mode

### Recording Techniques

**Voice Delivery:**
- Speak clearly and at moderate pace (~150 words/minute)
- Vary tone to maintain engagement
- Pause after key concepts (2-3 seconds)
- Avoid filler words (um, uh, like)
- Smile while speaking (comes through in voice)
- Hydrate before recording (dry mouth = clicks)

**Screen Recording:**
- Use mouse movements deliberately
- Highlight important code (cursor, zoom)
- Don't type too fast (viewers can't follow)
- Explain what you're doing while typing
- Show compiler errors and how to fix them
- Run programs and show output

**Face Cam (Optional):**
- Position camera at eye level
- Look at camera, not screen
- Maintain neutral expression
- Use gestures sparingly
- Dress professionally (casual but neat)

### Pacing Guidelines

**Per Minute of Video:**
- 150-160 words (normal speech)
- 10-15 lines of code (max)
- 2-3 visual aids (diagrams, animations)
- 1-2 key concepts

**Section Timing:**
- Intro: 30-45 seconds
- Each section: 3-6 minutes
- Code demos: 5-10 minutes
- Summary: 30-60 seconds

### Common Mistakes to Avoid

- **Mumbling:** Speak clearly, enunciate
- **Typing too fast:** Viewers can't follow
- **Skipping steps:** Explain everything
- **Assuming knowledge:** Define terms
- **Reading slides:** Use bullets, speak naturally
- **Long silences:** Keep ambient noise or fill with explanation
- **No preparation:** Practice demos before recording

---

## Post-Production

### Editing Workflow

**Step 1: Review Footage (1-2 hours per 15-min video)**
- Watch raw footage
- Mark sections to keep/cut
- Note mistakes, pauses, issues
- Create edit decision list (EDL)

**Step 2: Rough Cut (2-3 hours)**
- Remove mistakes and retakes
- Trim long pauses (>3 seconds)
- Rearrange for flow
- Add section headers

**Step 3: Fine Cut (1-2 hours)**
- Tighten pacing
- Add visual aids at appropriate points
- Zoom in on important code sections
- Add callouts, highlights

**Step 4: Audio Polish (1 hour)**
- Remove background noise
- Normalize audio levels
- Add compression (even out volume)
- EQ to enhance voice clarity

**Step 5: Export (30 minutes)**
- Format: MP4 (H.264 codec)
- Resolution: 1920x1080
- Frame rate: 30 fps
- Bitrate: 8-10 Mbps (1080p)
- Audio: AAC, 192 Kbps, 48 kHz

### Adding Visual Aids

**Diagrams and Animations:**
- Create in draw.io, PlantUML, or similar
- Export as PNG/SVG with transparent background
- Import into video editor
- Animate entrance/exit (fade in/out)
- Keep on screen for 5-10 seconds

**Code Highlights:**
- Use editor's built-in highlight
- Or add colored rectangles in video editor
- Zoom in on important sections
- Add arrows or callouts

**Text Overlays:**
- Section titles (3-5 seconds)
- Key points (5-10 seconds)
- Quiz questions (10-15 seconds)
- Keep minimal and readable

### Adding Captions

**Options:**

1. **Manual Captioning (Best Quality):**
   - Use YouTube's caption editor
   - Or: Rev.com ($1.25/minute)
   - Or: Otter.ai (auto-generate, then edit)

2. **Auto-Generated (Fast):**
   - YouTube auto-captions (free)
   - Otter.ai (free tier: 600 minutes/month)
   - accuracy: 80-90%

**Caption Guidelines:**
- Time with video (±0.5 seconds)
- Include [MUSIC], [APPLAUSE] for context
- Use proper punctuation and capitalization
- Identify speakers if multiple

---

## Publishing and Distribution

### Platform Options

**Primary: YouTube**
- Resolution: 1080p (1920x1080)
- Format: MP4 (H.264)
- Max file size: 256 GB (way more than needed)
- Max length: 12 hours
- Closed captions: Supported
- Monetization: Optional

**Alternative: PeerTube**
- Decentralized, open source
- No ads, no tracking
- Instance selection matters
- Good for tech audiences

**Backup: Vimeo**
- Higher quality playback
- No ads (paid plans)
- More professional audience
- Paid plans only

### Metadata

**Title Format:**
```
Video X: [Title] - JOTP Tutorial Series
```

**Description Template:**
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

**Tags:**
- JOTP, Java OTP, Java 26, Virtual Threads, Actor Model
- Fault Tolerance, Concurrency, Supervision
- Java Programming, Software Architecture
- (10-15 relevant tags max)

### Thumbnail Design

**Dimensions:** 1280x720 (16:9)
**File size:** Under 2 MB
**Format:** JPG or PNG

**Design Guidelines:**
- High contrast, bold colors
- Large, readable text (60pt+)
- Include video number and title
- Use JOTP branding colors
- Add face or logo for personalization
- Test at small size (YouTube thumbnail)

**Tools:**
- Canva (free templates)
- Figma (free for individuals)
- Adobe Photoshop (paid)

---

## Community Engagement

### Launch Strategy

**Phase 1: Soft Launch (Beta)**
- Upload first 3 videos as unlisted
- Share with small group (10-20 people)
- Gather feedback
- Fix issues
- Duration: 1 week

**Phase 2: Public Launch**
- Create playlist with all videos
- Publish announcement blog post
- Share on social media (Twitter, LinkedIn, Reddit)
- Announce on JOTP GitHub Discussions
- Duration: 1 week blitz

**Phase 3: Ongoing Engagement**
- Respond to comments within 24 hours
- Create Q&A follow-up videos
- Host live Q&A sessions
- Update videos based on feedback
- Duration: Ongoing

### Social Media Strategy

**Twitter:**
- 2-3 tweets per video launch
- Thread format: Hook → Problem → Solution → Link
- Tags: @jotp_framework, #Java, #Java26
- Engage with replies

**LinkedIn:**
- 1 post per video launch
- More detailed, professional tone
- Focus on business value
- Tag relevant communities

**Reddit:**
- r/programming, r/java, r/learnprogramming
- Follow subreddit rules
- Provide value, not just self-promotion
- Engage with comments

### Community Management

**Comment Guidelines:**
- Respond to all questions within 24 hours
- Be helpful and respectful
- Redirect to documentation when appropriate
- Highlight insightful comments
- Report/spam obvious spam

**Q&A Sessions:**
- Monthly live stream (YouTube Live)
- 60 minutes
- Answer questions from comments
- Demo new features
- Record for later viewing

**Contributions:**
- Accept community improvements to examples
- Highlight community projects
- Feature community members (with permission)
- Create "community spotlight" videos

---

## Quality Assurance

### Pre-Release Checklist

**Content:**
- [ ] Script reviewed by technical expert
- [ ] Code examples tested and working
- [ ] Factual accuracy verified
- [ ] No typos or factual errors
- [ ] All claims backed by evidence

**Technical:**
- [ ] Audio is clear and understandable
- [ ] Video is sharp and steady
- [ ] Code is readable (18pt+ font)
- [ ] No glitches or artifacts
- [ ] Captions are accurate

**Accessibility:**
- [ ] Captions/subtitles included
- [ ] Visual descriptions for screen readers
- [ ] High contrast colors
- [ ] Large, readable fonts
- [ ] Audio description (optional)

**Pedagogy:**
- [ ] Learning objectives clear
- [ ] Pacing is appropriate
- [ ] Examples build on each other
- [ ] Quiz questions test understanding
- [ ] Resources provided for further learning

### Testing

**Alpha Testing:**
- Show to 2-3 trusted colleagues
- Gather feedback on:
  - Clarity
  - Pacing
  - Technical accuracy
  - Engagement
- Iterate based on feedback

**Beta Testing:**
- Share with larger group (10-20 people)
- Test different skill levels
- Gather specific feedback:
  - What was confusing?
  - What was most helpful?
  - What would you add/remove?
- Final polish based on feedback

---

## Troubleshooting

### Common Issues

**Audio Problems:**

**Issue:** Background noise (hiss, hum)
- **Fix:** Use noise gate in post-production
- **Prevention:** Treat room, use better microphone

**Issue:** Plosives (popping P, B sounds)
- **Fix:** Pop filter essential
- **Prevention:** Move microphone slightly to side

**Issue:** Echo or reverb
- **Fix:** Add acoustic treatment
- **Prevention:** Record in carpeted room with curtains

**Video Problems:**

**Issue:** Blurry text
- **Fix:** Increase font size to 18pt+
- **Prevention:** Zoom in on important code

**Issue:** Screen recording lags
- **Fix:** Lower OBS bitrate, use hardware encoder
- **Prevention:** Close unnecessary apps

**Issue:** Poor lighting
- **Fix:** Add softbox or LED panel
- **Prevention:** Record during day with natural light

**Engagement Problems:**

**Issue:** Low view count
- **Fix:** Improve thumbnail and title
- **Prevention:** Share on social media, engage with community

**Issue:** Low retention (viewers drop off)
- **Fix:** Tighten pacing, add visual variety
- **Prevention:** Hook viewers in first 30 seconds

**Issue:** No comments/engagement
- **Fix:** Ask questions in video, respond to comments
- **Prevention:** Build community around series

---

## Resources

### Tools and Software

**Recording:**
- OBS Studio (https://obsproject.com/) - Free, open source
- Camtasia (https://www.techsmith.com/camtasia.html) - $299
- Loom (https://www.loom.com/) - Free tier available

**Editing:**
- DaVinci Resolve (https://www.blackmagicdesign.com/products/davinciresolve) - Free
- Final Cut Pro (https://www.apple.com/final-cut-pro/) - $299
- Adobe Premiere Pro (https://www.adobe.com/products/premiere.html) - $239/year

**Audio:**
- Audacity (https://www.audacityteam.org/) - Free
- Adobe Audition (https://www.adobe.com/products/audition.html) - $229/year

**Diagrams:**
- draw.io (https://app.diagrams.net/) - Free
- PlantUML (https://plantuml.com/) - Free
- Figma (https://www.figma.com/) - Free for individuals

### Learning Resources

**Video Production:**
- Techsmith Academy (https://www.techsmith.com/academy.html)
- YouTube Creator Academy (https://creatoracademy.youtube.com/)

**Audio:**
- Podcast Host's Guide to Microphones (search online)
- Audacity Tutorial (YouTube)

**Teaching:**
- "Teach Like a Champion" by Doug Lemov
- "Make It Stick" by Peter C. Brown

### Community

**JOTP:**
- GitHub: https://github.com/seanchatmangpt/jotp
- Discussions: https://github.com/seanchatmangpt/jotp/discussions

**Video Creators:**
- r/NewTubers (https://www.reddit.com/r/NewTubers/)
- r/VideoEditing (https://www.reddit.com/r/VideoEditing/)

---

## Metrics and Success

### Key Metrics to Track

**View Metrics:**
- Views per video
- View duration (average watch time)
- Retention rate (percentage who watch to end)
- Click-through rate (thumbnail performance)

**Engagement Metrics:**
- Likes and dislikes
- Comments per video
- Shares and embeds
- Subscribers gained

**Learning Metrics:**
- Quiz completion rate
- Code example downloads
- GitHub repository clones
- Community contributions

**Business Metrics:**
- Production cost per video
- Time investment per video
- ROI (if monetized)
- Career opportunities generated

### Targets

**First 3 Months:**
- Views: 10,000 per video
- Watch time: 40%+ retention
- Subscribers: 1,000+
- Comments: 50+ per video

**First 6 Months:**
- Views: 25,000 per video
- Watch time: 50%+ retention
- Subscribers: 5,000+
- Community contributions: 10+

**First Year:**
- Views: 50,000+ per video
- Watch time: 60%+ retention
- Subscribers: 10,000+
- Production deployments: 5+

---

## Maintenance

### Content Updates

**When to Update Videos:**
- Major JOTP version releases
- Breaking API changes
- New Java 26 features
- Community feedback on errors

**Update Process:**
1. Review outdated content
2. Re-record affected sections
3. Re-edit video
4. Update description and notes
5. Add "Updated" annotation

### Evergreen Content

**Create Timeless Content:**
- Focus on concepts, not just syntax
- Use principles, not just examples
- Teach problem-solving, not just solutions
- Provide multiple approaches

**Avoid:**
- Version-specific features (unless noted)
- Trending topics (quickly outdated)
- Controversial opinions (dates poorly)
- Humor that doesn't age well

---

## Conclusion

This production guide provides everything you need to create high-quality JOTP video tutorials. Remember that content quality matters more than production quality. Focus on clear explanations, working examples, and genuine value to the community.

**Start simple, iterate often, engage with your audience, and have fun!**

---

**Guide Version:** 1.0
**Last Updated:** 2026-03-16
**Next Review:** 2026-06-16

**Questions or Feedback?**
- GitHub: https://github.com/seanchatmangpt/jotp/discussions
- Email: community@jotp.dev
