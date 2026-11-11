import { Composition } from "remotion";
import { Ad, AD_FRAMES } from "./Ad";

export const RemotionRoot: React.FC = () => (
  <Composition id="PhoneControlAd" component={Ad} durationInFrames={AD_FRAMES} fps={30} width={1080} height={1920} />
);
