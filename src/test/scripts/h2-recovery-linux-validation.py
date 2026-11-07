"""仅用于 164 上指定隔离虚机的合成 H2 验收；power 模式会硬关其中一台测试虚机。"""
import json, os, re, shlex, subprocess, sys, time

if len(sys.argv) not in (3, 4):
    raise SystemExit('usage: h2-recovery-linux-validation.py /tmp/hajdbc-recovery-BUNDLE SCENARIO [manual|auto|power|timeout|owner]')
BUNDLE = sys.argv[1]
if not re.fullmatch(r'/tmp/hajdbc-recovery-[A-Za-z0-9_-]+', BUNDLE):
    raise ValueError('Bundle must be a dedicated /tmp/hajdbc-recovery-* test directory')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', sys.argv[2]):
    raise ValueError('Invalid test scenario name')
if len(sys.argv) > 3 and sys.argv[3] not in ('manual', 'auto', 'power', 'timeout', 'owner'):
    raise ValueError('Unknown test mode')
ROOT = BUNDLE + '/' + sys.argv[2]
AUTO = len(sys.argv) > 3 and sys.argv[3] in ('auto', 'power', 'timeout', 'owner')
POWER = len(sys.argv) > 3 and sys.argv[3] == 'power'
SLOW = len(sys.argv) > 3 and sys.argv[3] in ('timeout', 'owner')
OWNER = len(sys.argv) > 3 and sys.argv[3] == 'owner'
HOSTS = {'source': '192.168.231.11', 'target': '192.168.231.12'}
SSH = ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5', '-o', 'ServerAliveInterval=2', '-o', 'ServerAliveCountMax=2', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'UserKnownHostsFile=/tmp/hajdbc-recovery-20261107-known-hosts']
children = []
sequence = 0
powered_off = None
os.mkdir(ROOT)

def record(step, value): print(json.dumps({'step': step, 'value': value}), flush=True)

def remote(node, args):
    return subprocess.run(SSH + ['root@' + HOSTS[node], ' '.join(shlex.quote(x) for x in args)],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12, universal_newlines=True)

def start(node):
    if not any(entry[0] == node for entry in children):
        if remote(node, ['test', '-e', ROOT + '/' + node]).returncode == 0:
            raise RuntimeError('Existing guest scenario must not be overwritten: ' + node)
    result = remote(node, ['mkdir', '-p', ROOT + '/' + node])
    if result.returncode: raise RuntimeError(result.stderr)
    result = remote(node, ['rm', '-f', ROOT + '/' + node + '/ready'])
    if result.returncode: raise RuntimeError(result.stderr)
    log = open(ROOT + '/' + node + '-' + str(len(children)) + '.log', 'w')
    args = ['java', '-cp', BUNDLE + '/lib/*:' + BUNDLE + '/test-classes',
            'net.sf.hajdbc.dialect.h2.H2RecoveryClusterProcessHelper', ROOT, node, HOSTS['source'], HOSTS['target']]
    if AUTO: args.append('owner' if OWNER else 'auto')
    child = subprocess.Popen(SSH + ['root@' + HOSTS[node], ' '.join(shlex.quote(x) for x in args)],
                             stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, universal_newlines=True)
    entry = (node, child, log); children.append(entry); return entry

def waitfile(entry, path, seconds=65):
    node, child, _ = entry; end = time.monotonic() + seconds
    while True:
        result = remote(node, ['cat', path])
        if result.returncode == 0: return result.stdout
        if child.poll() is not None or time.monotonic() >= end: raise RuntimeError('Child failed/timed out: ' + node + ':' + path)
        time.sleep(.1)

def command(entry, action):
    global sequence
    sequence += 1; entry[1].stdin.write(str(sequence) + ' ' + action + '\n'); entry[1].stdin.flush()
    if action == 'CRASH': entry[1].wait(timeout=10); record('crashed', entry[0]); return
    value = waitfile(entry, ROOT + '/' + entry[0] + '/' + str(sequence), 40)
    record(entry[0] + ':' + action, value); return value

try:
    source, target = start('source'), start('target')
    waitfile(source, ROOT + '/source/ready'); record('ready', 'source')
    waitfile(target, ROOT + '/target/ready'); record('ready', 'target')
    end = time.monotonic() + 40
    while True:
        status = {entry[0]: command(entry, 'STATUS') for entry in children}
        host = [entry for entry in children if 'state=host,' in status[entry[0]]]
        if len(host) > 1: raise AssertionError('Multiple hosts: ' + str(status))
        if len(host) == 1: break
        if time.monotonic() >= end: raise AssertionError('No automatically elected host: ' + str(status))
        time.sleep(1)
    donor = command(host[0], 'ROWS')
    active = [source, target]
    if AUTO:
        end = time.monotonic() + 40
        while True:
            states = [command(entry, 'STATUS') for entry in active]
            if all('active=[source, target]' in value or 'active=[target, source]' in value for value in states): break
            if time.monotonic() >= end: raise AssertionError('Initial automatic activation timed out')
            time.sleep(1)
        replica = target if host[0][0] == 'source' else source
        if command(host[0], 'SLOW_ON' if SLOW else 'FAIL_ON') != 'OK': raise AssertionError('Could not inject test import failure')
        if SLOW:
            end = time.monotonic() + 25
            while command(replica, 'SLOW_COUNT') != '1':
                if time.monotonic() >= end: raise AssertionError('Server SQL never entered the gate')
                time.sleep(.2)
            before = command(replica, 'SELF_RECORD')
            if 'running=true' not in before: raise AssertionError('SQL must really run before timeout')
            if OWNER:
                if 'decision=PENDING' not in command(host[0], 'PEER_RECORD'): raise AssertionError('Owner must crash before deciding')
                command(host[0], 'CRASH')
                if command(replica, 'TRY_READ_LOCK') != 'false': raise AssertionError('Owner departure released unresolved write protection')
                new_owner = start(host[0][0]); waitfile(new_owner, ROOT + '/' + host[0][0] + '/ready')
                host = [new_owner]
                end = time.monotonic() + 12
                while command(replica, 'MEMBERS') != 'state=2,lock=2':
                    if time.monotonic() >= end: raise AssertionError('Old owner did not leave both views')
                    time.sleep(.2)
                if command(replica, 'TRY_READ_LOCK') != 'false': raise AssertionError('Unresolved fence disappeared after old owner was removed')
                record('owner_restarted_while_sql_running', command(replica, 'SELF_RECORD'))
            end = time.monotonic() + 15
            while 'decision=ABORT' not in command(host[0], 'PEER_RECORD'):
                if time.monotonic() >= end: raise AssertionError('RPC timeout did not persist abort')
                time.sleep(.2)
            # 再跨过一个 3 秒自动调度窗口；同一物理调用仍不能被下一轮重新清库。
            time.sleep(4)
            after = command(replica, 'SELF_RECORD')
            if before.split(',epoch=')[1].split(',')[0] != after.split(',epoch=')[1].split(',')[0]: raise AssertionError('New restore started while old SQL ran')
            if command(replica, 'SLOW_COUNT') != '1' or 'running=true' not in after: raise AssertionError('Physical import was retried or disappeared')
            record('single_physical_import_after_timeout', after)
            command(host[0], 'FAIL_ON'); command(replica, 'SLOW_RELEASE')
            end = time.monotonic() + 10
            while 'running=true' in command(replica, 'SELF_RECORD'):
                if time.monotonic() >= end: raise AssertionError('Late SQL did not exit')
                time.sleep(.1)
            if 'eligible=false' not in command(replica, 'STATUS'): raise AssertionError('Late SQL bypassed abort')
            command(host[0], 'SLOW_OFF')
        end = time.monotonic() + 30
        while 'decision=ABORT' not in command(replica, 'PROBE'):
            if time.monotonic() >= end: raise AssertionError('Expected durable abort after automatic failure')
            time.sleep(.5)
        if 'eligible=false' not in command(replica, 'STATUS'): raise AssertionError('Failed replica admitted')
        if SLOW:
            restarted = replica
        elif POWER:
            machine = 'manager-ha-lab-a' if replica[0] == 'source' else 'manager-ha-lab-b'
            network = subprocess.run(['virsh', 'domiflist', machine], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
            if network.returncode or 'manager-ha-lab-net' not in network.stdout:
                raise RuntimeError('Power test is restricted to the isolated lab network')
            before_boot = remote(replica[0], ['cat', '/proc/sys/kernel/random/boot_id']).stdout.strip()
            off = subprocess.run(['virsh', 'destroy', machine], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
            if off.returncode: raise RuntimeError(off.stderr)
            powered_off = machine
            record('hard_off', machine)
            on = subprocess.run(['virsh', 'start', machine], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
            if on.returncode: raise RuntimeError(on.stderr)
            powered_off = None
            try: replica[1].wait(timeout=12)
            except subprocess.TimeoutExpired:
                replica[1].terminate(); replica[1].wait(timeout=5)
            end = time.monotonic() + 75
            while True:
                boot = remote(replica[0], ['cat', '/proc/sys/kernel/random/boot_id'])
                if boot.returncode == 0: break
                if time.monotonic() >= end: raise RuntimeError('Guest did not return after restart')
                time.sleep(1)
            if before_boot == boot.stdout.strip(): raise AssertionError('Guest did not actually reboot')
            record('boot_changed', {'node': replica[0], 'old': before_boot, 'new': boot.stdout.strip()})
        else:
            command(replica, 'CRASH')
        if not SLOW:
            restarted = start(replica[0]); waitfile(restarted, ROOT + '/' + replica[0] + '/ready')
        if 'eligible=false' not in command(restarted, 'STATUS'): raise AssertionError('Restart bypassed isolation')
        active = [host[0], restarted]
        if command(host[0], 'FAIL_OFF') != 'OK': raise AssertionError('Could not remove test failure')
        end = time.monotonic() + 45
        while True:
            states = [command(entry, 'STATUS') for entry in active]
            if all('active=[source, target]' in value or 'active=[target, source]' in value for value in states): break
            if time.monotonic() >= end: raise AssertionError('Automatic recovery after restart timed out')
            time.sleep(1)
    else:
        for entry in children: command(entry, 'PROBE')
        if command(host[0], 'SYNC') != 'OK': raise AssertionError('Public activation synchronization failed')
    for entry in active:
        if command(entry, 'ROWS') != donor: raise AssertionError('Replicated rows differ')
        record('post_sync_status', command(entry, 'STATUS'))
    for entry in active: command(entry, 'STOP')
    for node, child, _ in children:
        if child.poll() is None and child.wait(timeout=20) != 0: raise RuntimeError('Normal cluster stop failed: ' + node)
    record('RESULT', 'PASS')
finally:
    if powered_off is not None:
        restored = subprocess.run(['virsh', 'start', powered_off], stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        record('restore_guest_after_failure', {'machine': powered_off, 'returncode': restored.returncode})
    for node, child, log in children:
        try:
            if child.poll() is None:
                child.stdin.close()
                try: child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    record('cleanup_timeout', node)
                    record('cluster_stopped', remote(node, ['cat', ROOT + '/' + node + '/stopped']).stdout)
                    script = 'for p in $(pgrep -f "^java -cp ' + BUNDLE + '/lib/.*H2RecoveryClusterProcessHelper ' + ROOT + '"); do kill -TERM "$p"; done'
                    remote(node, ['sh', '-c', script])
                    try: child.wait(timeout=5)
                    except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=5)
        except Exception as failure: record('cleanup_error', {'node': node, 'error': str(failure)})
        finally: log.close()
