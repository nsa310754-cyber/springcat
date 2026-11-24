/*
 * brkvm: 仮想マシンで KVM (CPU の仮想化の機能) を使えるかを確かめ、CPU のコアを調べ、
 * QEMU を決まったコアだけで動かすための小さな道具 (arm64 の端末用)。
 *
 *   libbrkvm.so cores
 *       コアごとの種類 (MIDR_EL1 の値から Cortex-X3・A715・A510 などの名前) と、同じ種類のまとまりを出す
 *   libbrkvm.so probe [コアの一覧]
 *       /dev/kvm を開き、仮想マシンと仮想 CPU を作り、ごく短い命令 (4 つ) を仮想 CPU で実際に動かす。
 *       段階ごとに「ok」か、失敗した理由 (errno) を出す。コアの一覧 (例 4-7) を渡すと、そのコアに固定して試す
 *   libbrkvm.so pin <コアの一覧> -- <プログラム> [引数…]
 *       自分をそのコアだけで動くように固定してから、プログラムに置き換わる (QEMU の起動に使う)。
 *       固定は子のプロセス・スレッドにも引き継がれる
 *
 * 端末の設定は何も変えない (読むのは /sys と /proc と /dev/kvm だけ)。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/kvm.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/xattr.h>
#include <unistd.h>

#define MAX_CPU 64

/* ARM の設計のコア (実装者 0x41) の部品番号と名前 */
static const struct {
    unsigned part;
    const char *name;
} PARTS[] = {
    {0xd03, "Cortex-A53"}, {0xd04, "Cortex-A35"}, {0xd05, "Cortex-A55"}, {0xd07, "Cortex-A57"},
    {0xd08, "Cortex-A72"}, {0xd09, "Cortex-A73"}, {0xd0a, "Cortex-A75"}, {0xd0b, "Cortex-A76"},
    {0xd0d, "Cortex-A77"}, {0xd41, "Cortex-A78"}, {0xd44, "Cortex-X1"}, {0xd46, "Cortex-A510"},
    {0xd47, "Cortex-A710"}, {0xd48, "Cortex-X2"}, {0xd4b, "Cortex-A78C"}, {0xd4d, "Cortex-A715"},
    {0xd4e, "Cortex-X3"}, {0xd80, "Cortex-A520"}, {0xd81, "Cortex-A720"}, {0xd82, "Cortex-X4"},
    {0xd85, "Cortex-X925"}, {0xd87, "Cortex-A725"},
};

static const char *part_name(unsigned impl, unsigned part)
{
    size_t i;
    if (impl != 0x41) return "ARM 以外の設計のコア";
    for (i = 0; i < sizeof(PARTS) / sizeof(PARTS[0]); i++) {
        if (PARTS[i].part == part) return PARTS[i].name;
    }
    return "不明なコア";
}

static int read_text(const char *path, char *buf, size_t n)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    ssize_t r;
    if (fd < 0) return -1;
    r = read(fd, buf, n - 1);
    close(fd);
    if (r < 0) return -1;
    buf[r] = 0;
    return 0;
}

struct core {
    int present;
    int online;
    uint64_t midr; /* 0 = 読めない */
    long maxfreq;  /* kHz。0 = 読めない */
};

static struct core cores[MAX_CPU];
static int ncpu;

/* /proc/cpuinfo の「CPU part」などから MIDR を組み立てる (sysfs の midr_el1 が読めないとき用) */
static void midr_from_cpuinfo(void)
{
    FILE *f = fopen("/proc/cpuinfo", "re");
    char line[256];
    int cpu = -1;
    unsigned impl = 0, var = 0, part = 0, rev = 0;
    if (!f) return;
    while (fgets(line, sizeof(line), f)) {
        unsigned v;
        if (sscanf(line, "processor : %d", &cpu) == 1) {
            impl = var = part = rev = 0;
        } else if (sscanf(line, "CPU implementer : %x", &v) == 1) {
            impl = v;
        } else if (sscanf(line, "CPU variant : %x", &v) == 1) {
            var = v;
        } else if (sscanf(line, "CPU part : %x", &v) == 1) {
            part = v;
        } else if (sscanf(line, "CPU revision : %u", &v) == 1) {
            rev = v;
            if (cpu >= 0 && cpu < MAX_CPU && cores[cpu].midr == 0 && impl) {
                cores[cpu].midr = (uint64_t)impl << 24 | var << 20 | 0xfu << 16 | part << 4 | rev;
            }
        }
    }
    fclose(f);
}

static void scan_cores(void)
{
    int i;
    char path[512], buf[64];
    ncpu = 0;
    for (i = 0; i < MAX_CPU; i++) {
        struct stat st;
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d", i);
        if (stat(path, &st) != 0) continue;
        cores[i].present = 1;
        ncpu = i + 1;
        cores[i].online = 1;
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/online", i);
        if (read_text(path, buf, sizeof(buf)) == 0 && buf[0] == '0') cores[i].online = 0;
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/regs/identification/midr_el1", i);
        if (read_text(path, buf, sizeof(buf)) == 0) cores[i].midr = strtoull(buf, NULL, 16);
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        if (read_text(path, buf, sizeof(buf)) == 0) cores[i].maxfreq = atol(buf);
    }
    midr_from_cpuinfo();
}

static unsigned impl_of(uint64_t m) { return (unsigned)(m >> 24) & 0xff; }
static unsigned part_of(uint64_t m) { return (unsigned)(m >> 4) & 0xfff; }

/* 同じ種類のコアのまとまりを出す。種類は実装者と部品番号で見る (版の違いは同じ種類とみなす) */
static void print_cores(void)
{
    int i, j, done[MAX_CPU] = {0};
    scan_cores();
    for (i = 0; i < ncpu; i++) {
        if (!cores[i].present) continue;
        printf("cpu %d online=%d midr=0x%016llx name=%s maxfreq=%ld\n", i, cores[i].online,
               (unsigned long long)cores[i].midr,
               cores[i].midr ? part_name(impl_of(cores[i].midr), part_of(cores[i].midr)) : "読めない",
               cores[i].maxfreq);
    }
    for (i = 0; i < ncpu; i++) {
        char list[256] = "";
        int count = 0;
        long freq = 0;
        if (!cores[i].present || done[i]) continue;
        for (j = i; j < ncpu; j++) {
            if (!cores[j].present || done[j]) continue;
            if (impl_of(cores[j].midr) != impl_of(cores[i].midr) || part_of(cores[j].midr) != part_of(cores[i].midr)) continue;
            done[j] = 1;
            snprintf(list + strlen(list), sizeof(list) - strlen(list), "%s%d", count ? "," : "", j);
            count++;
            if (cores[j].maxfreq > freq) freq = cores[j].maxfreq;
        }
        printf("group name=%s part=0x%03x count=%d cpus=%s maxfreq=%ld\n",
               cores[i].midr ? part_name(impl_of(cores[i].midr), part_of(cores[i].midr)) : "読めない",
               part_of(cores[i].midr), count, list, freq);
    }
}

/* "4-7" や "0,2,4-5" を CPU の集合にする。おかしければ -1 */
static int parse_cpus(const char *s, cpu_set_t *set)
{
    CPU_ZERO(set);
    while (*s) {
        char *end;
        long a = strtol(s, &end, 10), b;
        if (end == s || a < 0 || a >= MAX_CPU) return -1;
        b = a;
        if (*end == '-') {
            s = end + 1;
            b = strtol(s, &end, 10);
            if (end == s || b < a || b >= MAX_CPU) return -1;
        }
        for (; a <= b; a++) CPU_SET((int)a, set);
        s = end;
        if (*s == ',') s++;
        else if (*s) return -1;
    }
    return CPU_COUNT(set) > 0 ? 0 : -1;
}

static void on_alarm(int sig)
{
    (void)sig; /* KVM_RUN を EINTR で戻すためだけ */
}

static void label_of(const char *path, char *out, size_t n)
{
    ssize_t r = getxattr(path, "security.selinux", out, n - 1);
    if (r <= 0) snprintf(out, n, "(読めない)");
    else out[r] = 0;
}

/* メモリの登録を 1 つ試す (size 0 なら消す)。結果を "ok" か理由で返す */
static const char *try_slot(int vm, unsigned slot, uint64_t gpa, uint64_t size, void *host, unsigned flags)
{
    struct kvm_userspace_memory_region r;
    memset(&r, 0, sizeof(r));
    r.slot = slot;
    r.flags = flags;
    r.guest_phys_addr = gpa;
    r.memory_size = size;
    r.userspace_addr = (uintptr_t)host;
    if (ioctl(vm, KVM_SET_USER_MEMORY_REGION, &r) == 0) return "ok";
    return strerror(errno);
}

/* 段階ごとに確かめる。すべて通れば 0 */
static int probe(const char *cpus)
{
    char buf[256];
    int kvm, vm, vcpu, api, ipa, mmap_size;
    struct kvm_vcpu_init init;
    struct kvm_run *run;
    struct kvm_userspace_memory_region region;
    struct kvm_one_reg reg;
    uint64_t pc = 0x40000000ULL;
    void *mem;
    /* 仮想 CPU で動かす命令: 0x10000000 番地 (メモリの無い場所) に 0x42 を書く → QEMU の代わりに
       この道具へ「装置への書き込み」として戻ってくる。その後は同じ場所で止まる (b .) */
    static const uint32_t code[] = {
        0xD2A20000, /* movz x0, #0x1000, lsl #16 */
        0xD2800841, /* movz x1, #0x42 */
        0xF9000001, /* str  x1, [x0] */
        0x14000000, /* b    . */
    };

    if (cpus) {
        cpu_set_t set;
        if (parse_cpus(cpus, &set) != 0 || sched_setaffinity(0, sizeof(set), &set) != 0) {
            printf("pin ng コア %s に固定できません (%s)\n", cpus, strerror(errno));
            return 1;
        }
        printf("pin ok %s\n", cpus);
    }
    if (read_text("/proc/self/attr/current", buf, sizeof(buf)) == 0) printf("context %s\n", buf);
    printf("uid %d\n", (int)getuid());
    label_of("/dev/kvm", buf, sizeof(buf));
    printf("label /dev/kvm %s\n", buf);
    {
        struct stat st;
        if (stat("/dev/kvm", &st) != 0) {
            printf("open ng /dev/kvm がありません (%s)\n", strerror(errno));
            return 1;
        }
        printf("mode %o\n", st.st_mode & 07777);
    }

    kvm = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    if (kvm < 0) {
        int e = errno;
        printf("open ng errno=%d %s%s\n", e, strerror(e),
               e == EACCES ? " (ファイルの権限が足りていても、SELinux に拒否されるとこうなる)" : "");
        return 1;
    }
    printf("open ok\n");
    api = ioctl(kvm, KVM_GET_API_VERSION, 0);
    if (api != KVM_API_VERSION) {
        printf("api ng %d (%s)\n", api, api < 0 ? strerror(errno) : "版が違う");
        return 1;
    }
    printf("api ok %d\n", api);
    ipa = ioctl(kvm, KVM_CHECK_EXTENSION, KVM_CAP_ARM_VM_IPA_SIZE);
    printf("ipa %d\n", ipa);
    printf("cap_psci %d\n", ioctl(kvm, KVM_CHECK_EXTENSION, KVM_CAP_ARM_PSCI_0_2));
    printf("cap_pmu %d\n", ioctl(kvm, KVM_CHECK_EXTENSION, KVM_CAP_ARM_PMU_V3));
    printf("cap_maxvcpus %d\n", ioctl(kvm, KVM_CHECK_EXTENSION, KVM_CAP_MAX_VCPUS));

    vm = ioctl(kvm, KVM_CREATE_VM, 0);
    if (vm < 0) {
        printf("vm ng %s\n", strerror(errno));
        return 1;
    }
    printf("vm ok\n");
    mem = mmap(NULL, 0x10000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        printf("mem ng %s\n", strerror(errno));
        return 1;
    }
    memcpy(mem, code, sizeof(code));
    memset(&region, 0, sizeof(region));
    region.slot = 0;
    region.guest_phys_addr = pc;
    region.memory_size = 0x10000;
    region.userspace_addr = (uintptr_t)mem;
    if (ioctl(vm, KVM_SET_USER_MEMORY_REGION, &region) < 0) {
        printf("mem ng %s\n", strerror(errno));
        return 1;
    }
    printf("mem ok\n");
    {
        /* QEMU が使う種類の登録が通るか (保護モードの KVM は一部を拒否する)。試したら消す */
        void *extra = mmap(NULL, 0x10000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (extra != MAP_FAILED) {
            const char *ro = try_slot(vm, 1, 0x50000000ULL, 0x10000, extra, KVM_MEM_READONLY);
            if (strcmp(ro, "ok") == 0) try_slot(vm, 1, 0x50000000ULL, 0, extra, KVM_MEM_READONLY);
            printf("memtest 読み取り専用の領域 (KVM_MEM_READONLY): %s\n", ro);
            ro = try_slot(vm, 2, 0x60000000ULL, 0x10000, extra, KVM_MEM_LOG_DIRTY_PAGES);
            if (strcmp(ro, "ok") == 0) try_slot(vm, 2, 0x60000000ULL, 0, extra, KVM_MEM_LOG_DIRTY_PAGES);
            printf("memtest 書き込みを記録する領域 (KVM_MEM_LOG_DIRTY_PAGES): %s\n", ro);
        }
        printf("cap_readonly_mem %d\n", ioctl(kvm, KVM_CHECK_EXTENSION, KVM_CAP_READONLY_MEM));
    }

    vcpu = ioctl(vm, KVM_CREATE_VCPU, 0);
    if (vcpu < 0) {
        printf("vcpu ng %s\n", strerror(errno));
        return 1;
    }
    memset(&init, 0, sizeof(init));
    if (ioctl(vm, KVM_ARM_PREFERRED_TARGET, &init) < 0) {
        printf("vcpu ng 種類を決められません (%s)\n", strerror(errno));
        return 1;
    }
    printf("target %u\n", init.target);
    if (ioctl(vcpu, KVM_ARM_VCPU_INIT, &init) < 0) {
        printf("vcpu ng 初期化できません (%s)\n", strerror(errno));
        return 1;
    }
    printf("vcpu ok (コア %d の上で作った)\n", sched_getcpu());
    reg.id = KVM_REG_ARM64 | KVM_REG_SIZE_U64 | KVM_REG_ARM_CORE | KVM_REG_ARM_CORE_REG(regs.pc);
    reg.addr = (uintptr_t)&pc;
    if (ioctl(vcpu, KVM_SET_ONE_REG, &reg) < 0) {
        printf("run ng 開始の場所を決められません (%s)\n", strerror(errno));
        return 1;
    }
    mmap_size = ioctl(kvm, KVM_GET_VCPU_MMAP_SIZE, 0);
    run = mmap_size > 0 ? mmap(NULL, (size_t)mmap_size, PROT_READ | PROT_WRITE, MAP_SHARED, vcpu, 0) : MAP_FAILED;
    if (run == MAP_FAILED) {
        printf("run ng %s\n", strerror(errno));
        return 1;
    }
    {
        /* 5 秒で戻らなければ止める (命令が動かずに回り続けたとき用) */
        struct sigaction sa;
        struct itimerval it;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = on_alarm;
        sigaction(SIGALRM, &sa, NULL);
        memset(&it, 0, sizeof(it));
        it.it_value.tv_sec = 5;
        setitimer(ITIMER_REAL, &it, NULL);
    }
    if (ioctl(vcpu, KVM_RUN, 0) < 0) {
        printf("run ng %s\n", errno == EINTR ? "5 秒たっても戻りません" : strerror(errno));
        return 1;
    }
    if (run->exit_reason == KVM_EXIT_MMIO && run->mmio.is_write && run->mmio.phys_addr == 0x10000000ULL
        && run->mmio.data[0] == 0x42) {
        void *extra = mmap(NULL, 0x10000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        printf("run ok (仮想 CPU で命令が動いた。コア %d)\n", sched_getcpu());
        if (extra != MAP_FAILED) {
            /* 動かしたあとの登録の追加・削除 (装置の付け外しなどで QEMU が行う) */
            const char *add = try_slot(vm, 3, 0x70000000ULL, 0x10000, extra, 0);
            printf("memtest 動かしたあとの追加: %s\n", add);
            if (strcmp(add, "ok") == 0) printf("memtest 動かしたあとの削除: %s\n", try_slot(vm, 3, 0x70000000ULL, 0, extra, 0));
        }
        return 0;
    }
    printf("run ng 思っていたのと違う止まり方 (exit_reason=%u)\n", run->exit_reason);
    return 1;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (argc >= 2 && strcmp(argv[1], "cores") == 0) {
        print_cores();
        return 0;
    }
    if (argc >= 2 && strcmp(argv[1], "probe") == 0) {
        return probe(argc >= 3 && argv[2][0] ? argv[2] : NULL);
    }
    if (argc >= 5 && strcmp(argv[1], "pin") == 0 && strcmp(argv[3], "--") == 0) {
        cpu_set_t set;
        if (parse_cpus(argv[2], &set) != 0) {
            fprintf(stderr, "brkvm: コアの一覧がおかしい: %s\n", argv[2]);
            return 2;
        }
        if (sched_setaffinity(0, sizeof(set), &set) != 0) {
            fprintf(stderr, "brkvm: コア %s に固定できません: %s\n", argv[2], strerror(errno));
            return 2;
        }
        execv(argv[4], argv + 4);
        fprintf(stderr, "brkvm: %s を起動できません: %s\n", argv[4], strerror(errno));
        return 127;
    }
    fprintf(stderr, "使い方: %s cores | probe [コア] | pin <コア> -- <プログラム> [引数…]\n", argv[0]);
    return 2;
}
