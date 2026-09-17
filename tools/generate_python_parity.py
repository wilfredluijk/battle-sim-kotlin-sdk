"""Regenerate golden vectors with the original Python SDK importable on PYTHONPATH.

Reference: wilfredluijk/battle-sim-python-sdk@816fa9baba75c7294561a6f111aa2735f4bbdd01.
Normal Kotlin builds use the committed fixture and do not require Python.
"""
import json
import random
from dataclasses import asdict
from pathlib import Path
from naval_sdk import Command, Welcome, WorldView
from naval_sdk.helpers import bearing_to, distance, lead_target, signed_bearing_delta
from naval_sdk.tactical import Tracker, Gunner, Helm, Evader, Track

root = Path(__file__).resolve().parents[1]
f = json.loads((root / 'src/test/resources/protocol3.json').read_text())
w = Welcome.from_dict(f['welcome'])
rng = random.Random(816)
cases = []
for i in range(50):
    a = [rng.uniform(-300, 300), rng.uniform(-300, 300)]
    b = [rng.uniform(-300, 300), rng.uniform(-300, 300)]
    v = [rng.uniform(-90, 90), rng.uniform(-90, 90)]
    speed = rng.uniform(10, 120)
    cases.append(dict(kind='math', a=a, b=b, v=v, speed=speed,
                      expected=dict(distance=distance(a,b), bearing=bearing_to(a,b), lead=lead_target(a,b,v,speed))))
for i in range(25):
    target = [rng.uniform(100, 600), rng.uniform(100, 600)]
    vel = [rng.uniform(-9, 9), rng.uniform(-9, 9)]
    origin = [350, 350]
    cmd = Command(throttle=.6, rudder=-.2).fire_at(target, shooter_pos=origin, target_vel=vel, shell_speed=70)
    cases.append(dict(kind='command', target=target, vel=vel, origin=origin, expected=cmd.to_dict(i, 'parity'), tick=i))
for i in range(35):
    frame = json.loads(json.dumps(f['tick']))
    frame['tick'] = i + 1
    frame['self'].update(pos=[rng.uniform(0,700), rng.uniform(0,700)], heading_deg=rng.uniform(0,360), speed=rng.uniform(-2,15))
    if i % 2: frame['self']['powerup_status'] = [dict(id='overdrive', used=True, active_ticks_left=8)]
    view = WorldView.from_dict(frame)
    target = rng.uniform(0,360)
    cases.append(dict(kind='helm', view=frame, target=target, expected=Helm(w.ship_specs).steer_to_bearing(view.me,target)))
tracker = Tracker(w.ship_specs)
for i in range(1,61):
    frame = json.loads(json.dumps(f['tick']))
    frame['tick'] = i
    pos = [400 + i*.9, 300+i*.3]
    if i % 7 < 5:
        frame['contacts'] = [dict(id=f'c_{i}',kind='ship',pos=pos,bearing_deg=bearing_to([500,500],pos),
                                  range=distance([500,500],pos) if i%7 < 3 else None,confidence=.8)]
    tracks = tracker.update(WorldView.from_dict(frame))
    cases.append(dict(kind='tracker', view=frame, expected=[asdict(t) for t in tracks]))
for i in range(40):
    frame = json.loads(json.dumps(f['tick']))
    frame['tick'] = 10
    frame['self']['speed'] = rng.uniform(-2,15)
    frame['self']['heading_deg'] = rng.uniform(0,360)
    frame['self']['powerup_status'] = [dict(id='long_range_salvo',used=False,active_ticks_left=0)]
    if i % 2: frame['self']['powerup_status'].append(dict(id='heavy_shell',used=True,active_ticks_left=9))
    target = [rng.uniform(100,600),rng.uniform(100,600)]
    track = Track(1,'ship',target,target,[rng.uniform(-9,9),rng.uniform(-9,9)],10,1,10,.9,'active')
    view = WorldView.from_dict(frame)
    sol = Gunner(w.ship_specs).solve(view.me,track,view,activate_powerup='long_range_salvo')
    cases.append(dict(kind='gunner',view=frame,track=asdict(track),expected=asdict(sol) if sol else None))
evader = Evader(evasion_ticks=3,cooldown_ticks=3)
for i in range(1,31):
    frame=json.loads(json.dumps(f['tick'])); frame['tick']=i
    if i in [2,7,12,13,18,21]: frame['events']=[dict(type='hit',amount=10)]
    cmd=evader.update(WorldView.from_dict(frame))
    cases.append(dict(kind='evader',view=frame,expected=cmd.to_dict(i,'fixture-match-1') if cmd else None,state=evader.state.value))
path=root/'src/test/resources/python-parity.json'
path.write_text(json.dumps(dict(source_revision='816fa9baba75c7294561a6f111aa2735f4bbdd01',cases=cases),indent=2)+'\n')
print(f'Wrote {len(cases)} Python reference cases to {path.name}')
