export const SHIFT_TIME_ZONE = "America/Sao_Paulo";
export const SHIFT_START_HOUR = 19;
export const SHIFT_END_HOUR = 7;
export const PILOT_ANCHOR = Object.freeze({year:2026,month:9,day:26});
export const DEFAULT_TEAM_SHIFT_SCHEDULE = Object.freeze({
  mode:"12X36",startTime:"19:00",endTime:"07:00",workHours:12,restHours:36,timeZone:SHIFT_TIME_ZONE
});

const DAY_MS = 86400000;
const CLOCK_RE = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
const formatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: SHIFT_TIME_ZONE, hour12: false, hourCycle: "h23",
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit"
});

export const validShiftTime = value => typeof value === "string" && CLOCK_RE.test(value);

function parseClock(value) {
  if(!validShiftTime(value)) return null;
  const [hour,minute]=value.split(":").map(Number);
  return {hour,minute};
}

export function addClockHours(value,hours) {
  const clock=parseClock(value);if(!clock)return null;
  const total=(clock.hour*60+clock.minute+hours*60)%(24*60);
  return String(Math.floor(total/60)).padStart(2,"0")+":"+String(total%60).padStart(2,"0");
}

export function normalizeTeamShiftSchedule(raw={}) {
  const startTime=validShiftTime(raw?.startTime)?raw.startTime:DEFAULT_TEAM_SHIFT_SCHEDULE.startTime;
  return {
    mode:"12X36",startTime,endTime:addClockHours(startTime,12),workHours:12,restHours:36,timeZone:SHIFT_TIME_ZONE
  };
}

export function localParts(ms) {
  const p = Object.fromEntries(formatter.formatToParts(new Date(ms))
    .filter(x => x.type !== "literal").map(x => [x.type, Number(x.value)]));
  return {year:p.year,month:p.month,day:p.day,hour:p.hour===24?0:p.hour,minute:p.minute,second:p.second};
}

function addLocalDays(parts, days) {
  const d = new Date(Date.UTC(parts.year, parts.month - 1, parts.day + days));
  return {year:d.getUTCFullYear(),month:d.getUTCMonth()+1,day:d.getUTCDate()};
}

function localDayNumber(parts) {
  return Math.floor(Date.UTC(parts.year, parts.month - 1, parts.day) / DAY_MS);
}

const anchorDayNumber = localDayNumber(PILOT_ANCHOR);
export function isPilotScheduledDate(parts) {
  const diff = localDayNumber(parts) - anchorDayNumber;
  return diff >= 0 && diff % 2 === 0;
}

export function zonedLocalToUtcMs(parts) {
  const targetAsUtc = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute ?? 0, parts.second ?? 0);
  let guess = targetAsUtc;
  for (let i=0;i<3;i++) {
    const seen=localParts(guess);
    const seenAsUtc=Date.UTC(seen.year,seen.month-1,seen.day,seen.hour,seen.minute,seen.second);
    const delta=targetAsUtc-seenAsUtc;
    if(delta===0)break;
    guess+=delta;
  }
  return guess;
}

export function nextShiftEnd(ms=Date.now(),rawSchedule={}) {
  const schedule=normalizeTeamShiftSchedule(rawSchedule),clock=parseClock(schedule.endTime),now=localParts(ms);
  let date={year:now.year,month:now.month,day:now.day};
  const reached=now.hour>clock.hour ||
    (now.hour===clock.hour && now.minute>clock.minute) ||
    (now.hour===clock.hour && now.minute===clock.minute && now.second>=0);
  if(reached) date=addLocalDays(date,1);
  return zonedLocalToUtcMs({...date,hour:clock.hour,minute:clock.minute,second:0});
}

export function nextScheduledStart(ms = Date.now()) {
  const now=localParts(ms);
  let date={year:now.year,month:now.month,day:now.day};
  if(now.hour>=SHIFT_START_HOUR) date=addLocalDays(date,1);
  for(let i=0;i<4;i++){
    if(isPilotScheduledDate(date)) return zonedLocalToUtcMs({...date,hour:SHIFT_START_HOUR,minute:0,second:0});
    date=addLocalDays(date,1);
  }
  throw new Error("Unable to calculate next pilot shift");
}

export function officialShiftWindow(ms = Date.now()) {
  const now=localParts(ms);
  if(now.hour>=SHIFT_START_HOUR){
    const date={year:now.year,month:now.month,day:now.day};
    if(isPilotScheduledDate(date)){
      const end=addLocalDays(date,1);
      return {startMs:zonedLocalToUtcMs({...date,hour:SHIFT_START_HOUR,minute:0,second:0}),endMs:zonedLocalToUtcMs({...end,hour:SHIFT_END_HOUR,minute:0,second:0}),open:true};
    }
  }
  if(now.hour<SHIFT_END_HOUR){
    const today={year:now.year,month:now.month,day:now.day};
    const start=addLocalDays(today,-1);
    if(isPilotScheduledDate(start)){
      return {startMs:zonedLocalToUtcMs({...start,hour:SHIFT_START_HOUR,minute:0,second:0}),endMs:zonedLocalToUtcMs({...today,hour:SHIFT_END_HOUR,minute:0,second:0}),open:true};
    }
  }
  return {startMs:null,endMs:null,open:false,nextStartMs:nextScheduledStart(ms)};
}

export function isOfficialShiftWindow(ms = Date.now()) { return officialShiftWindow(ms).open; }

export function pilotBoundary(kind, ms = Date.now()) {
  const now=localParts(ms);
  const today={year:now.year,month:now.month,day:now.day};
  const scheduledDate=kind==="END"?addLocalDays(today,-1):today;
  const active=(kind==="START"||kind==="END")&&isPilotScheduledDate(scheduledDate);
  const pad=n=>String(n).padStart(2,"0");
  return {active,dateLabel:pad(today.day)+"/"+pad(today.month)+"/"+today.year,
    key:today.year+"-"+pad(today.month)+"-"+pad(today.day),scheduledDate};
}
