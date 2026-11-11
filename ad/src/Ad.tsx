import React from "react";
import {
  AbsoluteFill,
  Audio,
  Img,
  interpolate,
  OffthreadVideo,
  Sequence,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { loadFont } from "@remotion/google-fonts/Manrope";

const { fontFamily } = loadFont("normal", { weights: ["400", "500", "600", "700"], subsets: ["latin"] });

const INK = "#15141F";
const MUTED = "#8B8A9B";
const PURPLE = "#6C5CE7";
const SOFT = "#ECE9FF";
const PINK = "#D1489B";
const PINK_SOFT = "#FCE5F2";
const FPS = 30;
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

const ICON = {
  phone:
    "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3C19,1.9 18.1,1.01 17,1.01zM17,19H7V5h10V19z",
  shield: "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z",
  eye: "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
  touch:
    "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z",
  open: "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
  lock: "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
};

const Icon: React.FC<{ d: string; size: number; color: string }> = ({ d, size, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24">
    <path d={d} fill={color} />
  </svg>
);

/** 0 to 1, eased, starting `delay` frames into the current scene. */
const useEnter = (delay = 0) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping: 200 } });
};

const Rise: React.FC<{ delay?: number; y?: number; style?: React.CSSProperties; children: React.ReactNode }> = ({
  delay = 0,
  y = 46,
  style,
  children,
}) => {
  const p = useEnter(delay);
  return <div style={{ opacity: p, transform: `translateY(${(1 - p) * y}px)`, ...style }}>{children}</div>;
};

/** Fades the scene out over its last frames so cuts stay soft. */
const Scene: React.FC<{ frames: number; hold?: boolean; children: React.ReactNode }> = ({ frames, hold, children }) => {
  const frame = useCurrentFrame();
  const opacity = hold ? 1 : interpolate(frame, [frames - 9, frames - 1], [1, 0], clamp);
  return <AbsoluteFill style={{ opacity, alignItems: "center" }}>{children}</AbsoluteFill>;
};

const Tag: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: 14,
      padding: "16px 30px",
      borderRadius: 60,
      background: SOFT,
      color: PURPLE,
      fontSize: 34,
      fontWeight: 600,
      letterSpacing: 0.5,
    }}
  >
    {children}
  </div>
);

const Phone: React.FC<{ width: number; children: React.ReactNode }> = ({ width, children }) => (
  <div
    style={{
      width,
      height: (width * 2400) / 1080,
      borderRadius: width * 0.105,
      border: `${Math.round(width * 0.02)}px solid ${INK}`,
      overflow: "hidden",
      position: "relative",
      background: "#000",
      boxShadow: "0 50px 100px rgba(108,92,231,0.26), 0 10px 28px rgba(21,20,31,0.12)",
    }}
  >
    {children}
  </div>
);

// ------------------------------------------------------------------ 1. hook

// The two assistants send signals down to a phone, which reacts with taps.
const HOOK = 150;
const LINKS = [
  { name: "Claude", dot: "#D97757", x: 290, path: [290, 905, 290, 1010, 470, 985, 470, 1085] },
  { name: "ChatGPT", dot: "#10A37F", x: 790, path: [790, 905, 790, 1010, 610, 985, 610, 1085] },
];
const TAPS = [
  { x: 180, y: 150 },
  { x: 110, y: 330 },
  { x: 230, y: 500 },
];

const bezier = (p: number[], t: number) => {
  const u = 1 - t;
  const at = (a: number, b: number, c: number, d: number) =>
    u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
  return { x: at(p[0], p[2], p[4], p[6]), y: at(p[1], p[3], p[5], p[7]) };
};

const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const phoneIn = useEnter(26);
  const draw = interpolate(frame, [40, 62], [0, 1], clamp);
  const live = frame - 62; // signals start flowing once the lines are drawn
  const tap = TAPS[Math.max(0, Math.floor(live / 28)) % TAPS.length];
  const pulse = live < 0 ? 0 : (live % 28) / 28;
  return (
    <Scene frames={HOOK}>
      <div style={{ marginTop: 180, textAlign: "center", fontSize: 98, fontWeight: 700, lineHeight: 1.12, letterSpacing: -3 }}>
        <Rise>Claude and ChatGPT</Rise>
        <Rise delay={7}>can control</Rise>
        <Rise delay={14} style={{ color: PURPLE }}>
          your phone.
        </Rise>
      </div>
      <Rise delay={24} style={{ marginTop: 34, fontSize: 44, color: MUTED, fontWeight: 500 }}>
        Straight from the chat.
      </Rise>

      {LINKS.map((l, i) => {
        const p = spring({ frame: frame - 30 - i * 6, fps: FPS, config: { damping: 14 } });
        return (
          <div
            key={l.name}
            style={{
              position: "absolute",
              top: 800,
              left: l.x - 190,
              width: 380,
              height: 104,
              borderRadius: 52,
              background: "#FFFFFF",
              border: "2px solid #E6E4F0",
              boxShadow: "0 18px 40px rgba(108,92,231,0.16)",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 16,
              fontSize: 50,
              fontWeight: 700,
              opacity: Math.min(1, p),
              transform: `scale(${0.6 + 0.4 * p})`,
            }}
          >
            <div style={{ width: 24, height: 24, borderRadius: 12, background: l.dot }} />
            {l.name}
          </div>
        );
      })}

      <svg width={1080} height={1920} style={{ position: "absolute", left: 0, top: 0 }}>
        {LINKS.map((l, i) => {
          const d = `M${l.path[0]} ${l.path[1]} C ${l.path.slice(2).join(" ")}`;
          return (
            <g key={l.name}>
              <path
                d={d}
                pathLength={1}
                fill="none"
                stroke={PURPLE}
                strokeOpacity={0.35}
                strokeWidth={6}
                strokeLinecap="round"
                strokeDasharray={1}
                strokeDashoffset={1 - draw}
              />
              {live >= 0 &&
                [0, 14].map((offset) => {
                  const t = ((live + offset + i * 7) % 28) / 28;
                  const pt = bezier(l.path, t);
                  return <circle key={offset} cx={pt.x} cy={pt.y} r={11} fill={PURPLE} opacity={Math.sin(Math.PI * t)} />;
                })}
            </g>
          );
        })}
      </svg>

      <div
        style={{
          position: "absolute",
          top: 1085,
          left: 350,
          width: 380,
          height: 740,
          boxSizing: "border-box",
          borderRadius: 64,
          border: `12px solid ${INK}`,
          background: "#FFFFFF",
          boxShadow: "0 40px 90px rgba(108,92,231,0.24)",
          padding: "70px 34px 0",
          opacity: phoneIn,
          transform: `translateY(${(1 - phoneIn) * 160}px)`,
        }}
      >
        {[0, 1, 2].map((row) => {
          const active = live >= 0 && TAPS.indexOf(tap) === row;
          return (
            <div
              key={row}
              style={{
                height: 150,
                marginBottom: 28,
                borderRadius: 34,
                background: active ? SOFT : "#F3F2F8",
                display: "flex",
                alignItems: "center",
                padding: "0 26px",
                gap: 20,
              }}
            >
              <div style={{ width: 70, height: 70, borderRadius: 35, background: active ? PURPLE : "#E1DFEC" }} />
              <div>
                <div style={{ width: 150, height: 18, borderRadius: 9, background: active ? PURPLE : "#D9D7E6" }} />
                <div style={{ width: 96, height: 14, borderRadius: 7, background: "#E6E4F0", marginTop: 16 }} />
              </div>
            </div>
          );
        })}
        {live >= 0 && (
          <div
            style={{
              position: "absolute",
              left: tap.x - 60,
              top: tap.y - 60,
              width: 120,
              height: 120,
              borderRadius: 60,
              border: `8px solid ${PURPLE}`,
              opacity: 1 - pulse,
              transform: `scale(${0.35 + pulse})`,
            }}
          />
        )}
      </div>
    </Scene>
  );
};

// ------------------------------------------------------------- 1b. command

const COMMAND = 113;
const TYPED = "tunnel";
const Command: React.FC = () => {
  const frame = useCurrentFrame();
  const card = useEnter(8);
  const chars = Math.round(interpolate(frame, [26, 50], [0, TYPED.length], clamp));
  const cursorOn = frame % 16 < 9;
  const line = (at: number): React.CSSProperties => ({
    opacity: interpolate(frame, [at, at + 6], [0, 1], clamp),
    marginTop: 26,
  });
  return (
    <Scene frames={COMMAND}>
      <div style={{ marginTop: 400, textAlign: "center", fontSize: 116, fontWeight: 700, lineHeight: 1.1, letterSpacing: -3.5 }}>
        <Rise>One command</Rise>
        <Rise delay={7} style={{ color: PURPLE }}>
          connects them.
        </Rise>
      </div>
      <div
        style={{
          marginTop: 110,
          width: 920,
          boxSizing: "border-box",
          borderRadius: 48,
          background: "#16151D",
          padding: "44px 52px 64px",
          color: "#FFFFFF",
          fontFamily: "Consolas, 'Courier New', monospace",
          fontSize: 44,
          boxShadow: "0 50px 100px rgba(108,92,231,0.28)",
          opacity: card,
          transform: `translateY(${(1 - card) * 80}px)`,
        }}
      >
        <div style={{ display: "flex", gap: 14, marginBottom: 44 }}>
          {["#FF6B6B", "#FFD166", "#4ADE80"].map((c) => (
            <div key={c} style={{ width: 24, height: 24, borderRadius: 12, background: c }} />
          ))}
        </div>
        <div>
          <span style={{ color: "#8F82FF" }}>$ </span>
          {TYPED.slice(0, chars)}
          <span style={{ opacity: cursorOn ? 1 : 0, color: "#8F82FF" }}>▌</span>
        </div>
        <div style={{ ...line(58), color: "#9C9BAD" }}>Starting the tunnel...</div>
        <div style={{ ...line(70), color: "#9C9BAD" }}>Address for Claude / ChatGPT:</div>
        <div style={{ ...line(78), color: "#8F82FF", marginTop: 14 }}>https://your-phone.link/···/mcp</div>
      </div>
      <Rise delay={86} style={{ marginTop: 80, fontSize: 46, color: MUTED, fontWeight: 500, textAlign: "center" }}>
        Paste the address into Claude or ChatGPT.
      </Rise>
    </Scene>
  );
};

// -------------------------------------------------------------- 2. showcase

// Pieces of the real recording (seconds in demo.mp4), sped up where the emulator was slow.
const CUTS = [
  { from: 9.6, to: 14.6, rate: 2.5, label: "Opens Settings" },
  { from: 21.4, to: 24.2, rate: 1.4, label: "Taps the search box" },
  { from: 24.4, to: 33.2, rate: 3.2, label: "Types “dark theme”" },
  { from: 35.6, to: 38.6, rate: 1.5, label: "Picks the result" },
  { from: 40.4, to: 43.3, rate: 1, label: "Switches it on" },
];
let cursor = 0;
const STEPS = CUTS.map((c) => {
  const frames = Math.round(((c.to - c.from) / c.rate) * FPS);
  const step = { ...c, start: cursor, frames };
  cursor += frames;
  return step;
});
const SHOWCASE = cursor + 8;

const Showcase: React.FC = () => {
  const frame = useCurrentFrame();
  const enter = useEnter(0);
  const found = STEPS.findIndex((s) => frame < s.start + s.frames);
  const index = found === -1 ? STEPS.length - 1 : found;
  const step = STEPS[index];
  const local = frame - step.start;
  const captionIn = interpolate(local, [0, 9], [0, 1], clamp);
  return (
    <Scene frames={SHOWCASE}>
      <Rise style={{ marginTop: 110 }}>
        <Tag>Real recording · no hands</Tag>
      </Rise>
      <div
        style={{
          marginTop: 44,
          height: 96,
          fontSize: 76,
          fontWeight: 700,
          letterSpacing: -2,
          opacity: captionIn,
          transform: `translateY(${(1 - captionIn) * 24}px)`,
        }}
      >
        {step.label}
      </div>
      <div style={{ display: "flex", gap: 14, marginTop: 34 }}>
        {STEPS.map((s, i) => {
          const fill = i < index ? 1 : i > index ? 0 : Math.min(1, local / s.frames);
          return (
            <div key={s.label} style={{ width: 150, height: 10, borderRadius: 5, background: SOFT, overflow: "hidden" }}>
              <div style={{ width: `${fill * 100}%`, height: "100%", borderRadius: 5, background: PURPLE }} />
            </div>
          );
        })}
      </div>
      <div
        style={{
          position: "absolute",
          top: 470,
          opacity: enter,
          transform: `translateY(${(1 - enter) * 220}px) scale(${0.92 + 0.08 * enter})`,
        }}
      >
        <Phone width={600}>
          {STEPS.map((s) => (
            <Sequence key={s.label} from={s.start} durationInFrames={s.frames + 8}>
              <OffthreadVideo
                src={staticFile("demo.mp4")}
                trimBefore={Math.round(s.from * FPS)}
                playbackRate={s.rate}
                muted
                style={{ width: "100%", height: "100%" }}
              />
            </Sequence>
          ))}
        </Phone>
      </div>
    </Scene>
  );
};

// --------------------------------------------------------------- 3. control

const CONTROL = 108;
const Control: React.FC = () => {
  const frame = useCurrentFrame();
  const enter = useEnter(10);
  const ring = interpolate(frame % 40, [0, 20, 40], [0.25, 1, 0.25]);
  return (
    <Scene frames={CONTROL}>
      <div style={{ marginTop: 150, textAlign: "center", fontSize: 104, fontWeight: 700, lineHeight: 1.1, letterSpacing: -3 }}>
        <Rise>One switch.</Rise>
        <Rise delay={8} style={{ color: PURPLE }}>
          You stay in control.
        </Rise>
      </div>
      <div
        style={{
          position: "absolute",
          top: 500,
          opacity: enter,
          transform: `translateY(${(1 - enter) * 260}px)`,
        }}
      >
        <Phone width={590}>
          <Img src={staticFile("app.png")} style={{ width: "100%", height: "100%" }} />
          <div
            style={{
              position: "absolute",
              left: "3.4%",
              width: "93.2%",
              top: "16.9%",
              height: "8.9%",
              borderRadius: 60,
              border: `6px solid ${PURPLE}`,
              opacity: frame > 34 ? ring : 0,
            }}
          />
        </Phone>
      </div>
    </Scene>
  );
};

// ---------------------------------------------------------------- 4. safety

const SAFETY = 100;
const Safety: React.FC = () => (
  <Scene frames={SAFETY}>
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", textAlign: "center" }}>
      <Rise>
        <div
          style={{
            width: 190,
            height: 190,
            borderRadius: 95,
            background: PINK_SOFT,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <Icon d={ICON.shield} size={96} color={PINK} />
        </div>
      </Rise>
      <div style={{ marginTop: 60, fontSize: 112, fontWeight: 700, lineHeight: 1.1, letterSpacing: -3 }}>
        <Rise delay={8}>Banking apps</Rise>
        <Rise delay={15} style={{ color: PURPLE }}>
          stay locked.
        </Rise>
      </div>
      <div style={{ display: "flex", gap: 20, marginTop: 70 }}>
        {["Bank", "UPI", "Wallet"].map((name, i) => (
          <Rise key={name} delay={30 + i * 6}>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 12,
                padding: "20px 34px",
                borderRadius: 60,
                border: "2px solid #E6E4F0",
                fontSize: 40,
                fontWeight: 600,
              }}
            >
              <Icon d={ICON.lock} size={36} color={INK} />
              {name}
            </div>
          </Rise>
        ))}
      </div>
      <Rise delay={54} style={{ marginTop: 60, fontSize: 44, color: MUTED, fontWeight: 500 }}>
        And every action is logged.
      </Rise>
    </AbsoluteFill>
  </Scene>
);

// ------------------------------------------------------------------- 5. end

const END = 110;
const End: React.FC = () => {
  const pop = useEnter(0);
  const chips = [
    { d: ICON.eye, text: "Sees the screen" },
    { d: ICON.touch, text: "Taps and types" },
    { d: ICON.open, text: "Opens apps" },
  ];
  return (
    <Scene frames={END} hold>
      <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", textAlign: "center" }}>
        <div
          style={{
            width: 230,
            height: 230,
            borderRadius: 64,
            background: PURPLE,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            transform: `scale(${0.6 + 0.4 * pop})`,
            opacity: pop,
            boxShadow: "0 40px 80px rgba(108,92,231,0.35)",
          }}
        >
          <Icon d={ICON.phone} size={124} color="#FFFFFF" />
        </div>
        <Rise delay={8} style={{ marginTop: 56, fontSize: 116, fontWeight: 700, letterSpacing: -3 }}>
          Phone Control
        </Rise>
        <Rise delay={16} style={{ marginTop: 14, fontSize: 46, color: MUTED, fontWeight: 500 }}>
          Let Claude or ChatGPT handle the taps.
        </Rise>
        <div style={{ display: "flex", flexDirection: "column", gap: 20, marginTop: 80, alignItems: "center" }}>
          {chips.map((c, i) => (
            <Rise key={c.text} delay={30 + i * 7}>
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 18,
                  padding: "22px 40px",
                  borderRadius: 60,
                  background: SOFT,
                  fontSize: 42,
                  fontWeight: 600,
                }}
              >
                <Icon d={c.d} size={42} color={PURPLE} />
                {c.text}
              </div>
            </Rise>
          ))}
        </div>
        <Rise delay={60} style={{ marginTop: 80, fontSize: 34, color: MUTED, fontWeight: 500, lineHeight: 1.6 }}>
          Connects through MCP connectors
          <br />
          Not affiliated with Anthropic or OpenAI
        </Rise>
      </AbsoluteFill>
    </Scene>
  );
};

// ----------------------------------------------------------------------- ad

const SCENES: { frames: number; view: React.FC }[] = [
  { frames: HOOK, view: Hook },
  { frames: COMMAND, view: Command },
  { frames: SHOWCASE, view: Showcase },
  { frames: CONTROL, view: Control },
  { frames: SAFETY, view: Safety },
  { frames: END, view: End },
];
export const AD_FRAMES = SCENES.reduce((sum, s) => sum + s.frames, 0);

export const Ad: React.FC = () => {
  let from = 0;
  return (
    <AbsoluteFill style={{ background: "#FFFFFF", color: INK, fontFamily }}>
      <Audio
        src={staticFile("bgm.wav")}
        volume={(f) => interpolate(f, [0, 15, AD_FRAMES - 50, AD_FRAMES - 1], [0, 0.6, 0.6, 0], clamp)}
      />
      {SCENES.map((s, i) => {
        const start = from;
        from += s.frames;
        const View = s.view;
        return (
          <Sequence key={i} from={start} durationInFrames={s.frames}>
            <View />
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};
