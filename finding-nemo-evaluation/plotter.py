import os
import glob
import csv
import subprocess

try:
    import pandas as pd
    import matplotlib.pyplot as plt
    import seaborn as sns
    HAS_MPL = True
    # Springer LLNCS style configurations
    plt.rcParams.update({
        'font.family': 'serif',
        'font.serif': ['Times New Roman', 'Times', 'DejaVu Serif'],
        'font.size': 10,
        'axes.labelsize': 10,
        'axes.titlesize': 10,
        'xtick.labelsize': 9,
        'ytick.labelsize': 9,
        'legend.fontsize': 9,
        'figure.titlesize': 12,
        'figure.dpi': 300,
        'savefig.dpi': 300,
        'savefig.format': 'pdf',
        'savefig.bbox': 'tight'
    })
except ImportError:
    HAS_MPL = False


def plot_test1(member_name):
    files = glob.glob(f"{member_name}_test1.csv")
    if not files:
        print("No data for Test 1")
        return
    if not HAS_MPL:
        print("[INFO] Skipping Test 1 matplotlib rendering (requires pandas/matplotlib)")
        return
        
    df = pd.concat([pd.read_csv(f) for f in files])
    # Group by k to get average success rate and total trials
    grouped = df.groupby('k').agg({'success_rate': 'mean', 'trials': 'sum'}).reset_index()
    
    fig, ax = plt.subplots(figsize=(6, 4))
    sns.lineplot(data=grouped, x='k', y='success_rate', marker='o', ax=ax, color='black', label='% Success')
    
    # Annotate with trial count
    for _, row in grouped.iterrows():
        ax.text(row['k'], row['success_rate'] + 2, f"n={int(row['trials'])}", 
                ha='center', va='bottom', fontsize=8)
                
    ax.set_xlabel('Target Degree Limit ($k$)')
    ax.set_ylabel('Success Rate (%)')
    ax.set_title('Test 1: Bootstrap Success vs. $k$')
    ax.set_ylim(-5, 115)
    ax.grid(True, linestyle='--', alpha=0.7)
    
    plt.savefig('test1_bootstrap.pdf')
    plt.savefig('test1_bootstrap.eps')
    plt.close()
    print("Saved test1_bootstrap.pdf and test1_bootstrap.eps")

def plot_test2(member_name):
    files = glob.glob(f"{member_name}_test2.csv")
    if not files:
        print("No data for Test 2")
        return
    if not HAS_MPL:
        print("[INFO] Skipping Test 2 matplotlib rendering (requires pandas/matplotlib)")
        return
        
    df = pd.concat([pd.read_csv(f) for f in files])
    
    # 2A: Line chart % success vs g (for a fixed k, or averaged over k)
    fig, ax = plt.subplots(figsize=(6, 4))
    sns.lineplot(data=df, x='g', y='success_rate', hue='k', marker='s', ax=ax, palette='tab10')
    ax.set_xlabel('Sequential Additions ($g$)')
    ax.set_ylabel('Success Rate (%)')
    ax.set_title('Test 2: Success Rate vs. Sequential Additions')
    ax.set_ylim(-5, 115)
    ax.grid(True, linestyle='--', alpha=0.7)
    plt.savefig('test2_success_vs_g.pdf')
    plt.savefig('test2_success_vs_g.eps')
    plt.close()
    print("Saved test2_success_vs_g.pdf and test2_success_vs_g.eps")
    
    # 2B: Line chart % success vs k (averaged over g)
    fig, ax = plt.subplots(figsize=(6, 4))
    sns.lineplot(data=df, x='k', y='success_rate', marker='s', ax=ax, color='black')
    ax.set_xlabel('Target Degree Limit ($k$)')
    ax.set_ylabel('Success Rate (%)')
    ax.set_title('Test 2: Success Rate vs. $k$')
    ax.set_ylim(-5, 115)
    ax.grid(True, linestyle='--', alpha=0.7)
    plt.savefig('test2_success_vs_k.pdf')
    plt.savefig('test2_success_vs_k.eps')
    plt.close()
    print("Saved test2_success_vs_k.pdf and test2_success_vs_k.eps")
    
    # 2C: Bubble chart mapping k vs g
    grouped = df.groupby(['k', 'g']).agg({'success_rate': 'mean', 'trials': 'sum'}).reset_index()
    
    fig, ax = plt.subplots(figsize=(6, 4))
    scatter = ax.scatter(grouped['k'], grouped['g'], s=grouped['trials']*10, 
                         c=grouped['success_rate'], cmap='gray', alpha=0.7, edgecolors='black',
                         vmin=0, vmax=100)
    
    # Add labels
    for _, row in grouped.iterrows():
        ax.text(row['k'], row['g'], f"{row['success_rate']:.0f}%", 
                ha='center', va='center', fontsize=8, color='white' if row['success_rate'] < 50 else 'black')
                
    cbar = plt.colorbar(scatter, ax=ax)
    cbar.set_label('Success Rate (%)')
    cbar.set_ticks([0, 20, 40, 60, 80, 100])
    
    ax.set_xlabel('Target Degree Limit ($k$)')
    ax.set_ylabel('Sequential Additions ($g$)')
    ax.set_title('Test 2: Bubble Chart of $k$ vs $g$ (Size = Trials)')
    plt.savefig('test2_bubble_k_vs_g.pdf')
    plt.savefig('test2_bubble_k_vs_g.eps')
    plt.close()
    print("Saved test2_bubble_k_vs_g.pdf and test2_bubble_k_vs_g.eps")

def plot_test3(member_name):
    files = glob.glob(f"{member_name}_test3.csv")
    if not files:
        print("No data for Test 3")
        return
    if HAS_MPL:
        df = pd.concat([pd.read_csv(f) for f in files])
        grouped = df.groupby(['k', 's']).agg({'success_rate': 'mean', 'trials': 'sum'}).reset_index()
        
        fig, ax = plt.subplots(figsize=(6, 4))
        scatter = ax.scatter(grouped['k'], grouped['s'], s=grouped['trials']*10, 
                             c=grouped['success_rate'], cmap='gray', alpha=0.7, edgecolors='black')
        
        for _, row in grouped.iterrows():
            ax.text(row['k'], row['s'], f"{row['success_rate']:.0f}%", 
                    ha='center', va='center', fontsize=8, color='white' if row['success_rate'] < 50 else 'black')
                    
        cbar = plt.colorbar(scatter, ax=ax)
        cbar.set_label('Success Rate (%)')
        
        ax.set_xlabel('Target Degree Limit ($k$)')
        ax.set_ylabel('Concurrent Burst Size ($s$)')
        ax.set_title('Test 3: Bubble Chart of $k$ vs $s$ (Size = Trials)')
        plt.savefig('test3_bubble_k_vs_s.pdf')
        plt.savefig('test3_bubble_k_vs_s.eps')
        plt.close()
        print("Saved test3_bubble_k_vs_s.pdf and test3_bubble_k_vs_s.eps")

        # 3B: Line chart % success vs s for different values of k
        fig, ax = plt.subplots(figsize=(6, 4))
        sns.lineplot(data=df, x='s', y='success_rate', hue='k', marker='o', ax=ax, palette='tab10')
        ax.set_xlabel('Concurrent Burst Size ($s$)')
        ax.set_ylabel('Success Rate (%)')
        ax.set_title('Test 3: Success Rate vs. Concurrent Burst Size ($s$)')
        ax.set_ylim(-5, 115)
        ax.grid(True, linestyle='--', alpha=0.7)
        plt.savefig('test3_success_vs_s.pdf')
        plt.savefig('test3_success_vs_s.eps')
        plt.close()
        print("Saved test3_success_vs_s.pdf and test3_success_vs_s.eps")
    else:
        _fallback_plot_test3(files)

def _fallback_plot_test3(files):
    data = {}
    for fpath in files:
        with open(fpath, 'r') as f:
            reader = csv.DictReader(f)
            for r in reader:
                k = int(r['k'])
                s = int(r['s'])
                succ = float(r['success_rate'])
                trials = int(r['trials'])
                if (k, s) not in data:
                    data[(k, s)] = {'succ': succ, 'trials': trials, 'count': 1}
                else:
                    data[(k, s)]['succ'] = (data[(k, s)]['succ'] * data[(k, s)]['count'] + succ) / (data[(k, s)]['count'] + 1)
                    data[(k, s)]['trials'] += trials
                    data[(k, s)]['count'] += 1

    if not data:
        return

    k_vals = sorted(list(set(k for k, s in data.keys())))
    s_vals = sorted(list(set(s for k, s in data.keys())))

    # 3A: Bubble chart
    tex_bubble = r'''\documentclass[tikz,border=3pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\begin{document}
\begin{tikzpicture}
\begin{axis}[
    width=8.5cm, height=6.5cm,
    xlabel={Target Degree Limit ($k$)},
    ylabel={Concurrent Burst Size ($s$)},
    title={Test 3: Bubble Chart of $k$ vs $s$ (Size = Trials)},
    xmin=3, xmax=11,
    ymin=0, ymax=32,
    xtick={4,6,8,10},
    ytick={1,5,10,15,20,25,30},
    grid=both,
    grid style={dashed, gray!20}
]
'''
    for (k, s), val in data.items():
        succ = val['succ']
        gray_pct = int(succ)
        r = 0.28
        col = 'white' if succ < 50 else 'black'
        tex_bubble += f'\\draw[fill=black!{100-gray_pct}!white, draw=black, opacity=0.8] (axis cs:{k},{s}) circle ({r}cm);\n'
        tex_bubble += f'\\node[font=\\tiny, text={col}] at (axis cs:{k},{s}) {{{int(round(succ))}\\%}};\n'

    tex_bubble += r'''\end{axis}
\end{tikzpicture}
\end{document}'''

    with open('temp_t3_bubble.tex', 'w') as f:
        f.write(tex_bubble)
    subprocess.run(['pdflatex', '-interaction=nonstopmode', 'temp_t3_bubble.tex'], stdout=subprocess.DEVNULL)
    subprocess.run(['pdftops', '-eps', 'temp_t3_bubble.pdf', 'test3_bubble_k_vs_s.eps'])
    if os.path.exists('temp_t3_bubble.pdf'):
        os.replace('temp_t3_bubble.pdf', 'test3_bubble_k_vs_s.pdf')
    for ext in ['aux', 'log', 'tex']:
        if os.path.exists(f'temp_t3_bubble.{ext}'):
            os.remove(f'temp_t3_bubble.{ext}')
    print("Saved test3_bubble_k_vs_s.pdf and test3_bubble_k_vs_s.eps")

    # 3B: Line chart
    tex_line = r'''\documentclass[tikz,border=3pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\begin{document}
\begin{tikzpicture}
\begin{axis}[
    width=8.5cm, height=6.0cm,
    xlabel={Concurrent Burst Size ($s$)},
    ylabel={Success Rate (\%)},
    title={Test 3: Success Rate vs. Concurrent Burst Size ($s$)},
    ymin=-5, ymax=115,
    xmin=0, xmax=31,
    grid=both,
    grid style={dashed, gray!30},
    legend pos=north east,
    legend style={font=\footnotesize}
]
'''
    colors = ['blue', 'red', 'teal', 'magenta', 'orange']
    for idx, k in enumerate(k_vals):
        coords = ' '.join(f'({s},{data[(k,s)]["succ"]:.1f})' for s in s_vals if (k, s) in data)
        col = colors[idx % len(colors)]
        tex_line += f'\\addplot[mark=*, color={col}, thick] coordinates {{ {coords} }};\n'
        tex_line += f'\\addlegendentry{{$k={k}$}}\n'
    tex_line += r'''\end{axis}
\end{tikzpicture}
\end{document}'''

    with open('temp_t3_line.tex', 'w') as f:
        f.write(tex_line)
    subprocess.run(['pdflatex', '-interaction=nonstopmode', 'temp_t3_line.tex'], stdout=subprocess.DEVNULL)
    subprocess.run(['pdftops', '-eps', 'temp_t3_line.pdf', 'test3_success_vs_s.eps'])
    if os.path.exists('temp_t3_line.pdf'):
        os.replace('temp_t3_line.pdf', 'test3_success_vs_s.pdf')
    for ext in ['aux', 'log', 'tex']:
        if os.path.exists(f'temp_t3_line.{ext}'):
            os.remove(f'temp_t3_line.{ext}')
    print("Saved test3_success_vs_s.pdf and test3_success_vs_s.eps")


def plot_test4(member_name):
    files = glob.glob(f"{member_name}_test4.csv")
    if not files:
        print("No data for Test 4")
        return
        
    if HAS_MPL:
        df = pd.concat([pd.read_csv(f) for f in files])
        grouped = df.groupby(['k', 's']).agg({'success_rate': 'mean', 'trials': 'sum'}).reset_index()
        
        # 4A: Bubble chart mapping k vs s
        fig, ax = plt.subplots(figsize=(6, 4))
        scatter = ax.scatter(grouped['k'], grouped['s'], s=grouped['trials']*10, 
                             c=grouped['success_rate'], cmap='gray', alpha=0.7, edgecolors='black',
                             vmin=0, vmax=100)
        
        for _, row in grouped.iterrows():
            ax.text(row['k'], row['s'], f"{row['success_rate']:.0f}%", 
                    ha='center', va='center', fontsize=8, color='white' if row['success_rate'] < 50 else 'black')
                    
        cbar = plt.colorbar(scatter, ax=ax)
        cbar.set_label('Success Rate (%)')
        cbar.set_ticks([0, 20, 40, 60, 80, 100])
        
        ax.set_xlabel('Target Degree Limit ($k$)')
        ax.set_ylabel('Concurrent Burst Size ($s$)')
        ax.set_title(r'Test 4: Bubble Chart of $k$ vs $s$ (Initial $N=100\mathrm{K}$, Size = Trials)')
        plt.savefig('test4_bubble_k_vs_s.pdf')
        plt.savefig('test4_bubble_k_vs_s.eps')
        plt.close()
        print("Saved test4_bubble_k_vs_s.pdf and test4_bubble_k_vs_s.eps")

        # 4B: Line chart % success vs s for different values of k
        fig, ax = plt.subplots(figsize=(6, 4))
        sns.lineplot(data=df, x='s', y='success_rate', hue='k', marker='o', ax=ax, palette='tab10')
        ax.set_xlabel('Concurrent Burst Size ($s$)')
        ax.set_ylabel('Success Rate (%)')
        ax.set_title(r'Test 4: Success Rate vs. Concurrent Burst Size ($s$, Initial $N=100\mathrm{K}$)')
        ax.set_ylim(-5, 115)
        ax.grid(True, linestyle='--', alpha=0.7)
        plt.savefig('test4_success_vs_s.pdf')
        plt.savefig('test4_success_vs_s.eps')
        plt.close()
        print("Saved test4_success_vs_s.pdf and test4_success_vs_s.eps")
    else:
        _fallback_plot_test4(files)

def _fallback_plot_test4(files):
    data = {}
    for fpath in files:
        with open(fpath, 'r') as f:
            reader = csv.DictReader(f)
            for r in reader:
                k = int(r['k'])
                s = int(r['s'])
                succ = float(r['success_rate'])
                trials = int(r['trials'])
                if (k, s) not in data:
                    data[(k, s)] = {'succ': succ, 'trials': trials, 'count': 1}
                else:
                    data[(k, s)]['succ'] = (data[(k, s)]['succ'] * data[(k, s)]['count'] + succ) / (data[(k, s)]['count'] + 1)
                    data[(k, s)]['trials'] += trials
                    data[(k, s)]['count'] += 1

    if not data:
        return

    k_vals = sorted(list(set(k for k, s in data.keys())))
    s_vals = sorted(list(set(s for k, s in data.keys())))

    # 4A: Bubble chart
    tex_bubble = r'''\documentclass[tikz,border=3pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\begin{document}
\begin{tikzpicture}
\begin{axis}[
    width=8.5cm, height=6.5cm,
    xlabel={Target Degree Limit ($k$)},
    ylabel={Concurrent Burst Size ($s$)},
    title={Test 4: Bubble Chart of $k$ vs $s$ (Initial $N=100\mathrm{K}$, Size = Trials)},
    xmin=3, xmax=11,
    ymin=0, ymax=32,
    xtick={4,6,8,10},
    ytick={1,5,10,15,20,25,30},
    grid=both,
    grid style={dashed, gray!20}
]
'''
    for (k, s), val in data.items():
        succ = val['succ']
        gray_pct = int(succ)
        r = 0.28
        col = 'white' if succ < 50 else 'black'
        tex_bubble += f'\\draw[fill=black!{100-gray_pct}!white, draw=black, opacity=0.8] (axis cs:{k},{s}) circle ({r}cm);\n'
        tex_bubble += f'\\node[font=\\tiny, text={col}] at (axis cs:{k},{s}) {{{int(succ)}\\%}};\n'

    tex_bubble += r'''\end{axis}
\end{tikzpicture}
\end{document}'''

    with open('temp_t4_bubble.tex', 'w') as f:
        f.write(tex_bubble)
    subprocess.run(['pdflatex', '-interaction=nonstopmode', 'temp_t4_bubble.tex'], stdout=subprocess.DEVNULL)
    subprocess.run(['pdftops', '-eps', 'temp_t4_bubble.pdf', 'test4_bubble_k_vs_s.eps'])
    if os.path.exists('temp_t4_bubble.pdf'):
        os.replace('temp_t4_bubble.pdf', 'test4_bubble_k_vs_s.pdf')
    for ext in ['aux', 'log', 'tex']:
        if os.path.exists(f'temp_t4_bubble.{ext}'):
            os.remove(f'temp_t4_bubble.{ext}')
    print("Saved test4_bubble_k_vs_s.pdf and test4_bubble_k_vs_s.eps")

    # 4B: Line chart
    tex_line = r'''\documentclass[tikz,border=3pt]{standalone}
\usepackage{pgfplots}
\pgfplotsset{compat=1.18}
\begin{document}
\begin{tikzpicture}
\begin{axis}[
    width=8.5cm, height=6.0cm,
    xlabel={Concurrent Burst Size ($s$)},
    ylabel={Success Rate (\%)},
    title={Test 4: Success Rate vs. Concurrent Burst Size ($s$, Initial $N=100\mathrm{K}$)},
    ymin=-5, ymax=115,
    xmin=0, xmax=31,
    grid=both,
    grid style={dashed, gray!30},
    legend pos=south west,
    legend style={font=\footnotesize}
]
'''
    colors = ['blue', 'red', 'teal', 'magenta', 'orange']
    for idx, k in enumerate(k_vals):
        coords = ' '.join(f'({s},{data[(k,s)]["succ"]})' for s in s_vals if (k, s) in data)
        col = colors[idx % len(colors)]
        tex_line += f'\\addplot[mark=*, color={col}, thick] coordinates {{ {coords} }};\n'
        tex_line += f'\\addlegendentry{{$k={k}$}}\n'
    tex_line += r'''\end{axis}
\end{tikzpicture}
\end{document}'''

    with open('temp_t4_line.tex', 'w') as f:
        f.write(tex_line)
    subprocess.run(['pdflatex', '-interaction=nonstopmode', 'temp_t4_line.tex'], stdout=subprocess.DEVNULL)
    subprocess.run(['pdftops', '-eps', 'temp_t4_line.pdf', 'test4_success_vs_s.eps'])
    if os.path.exists('temp_t4_line.pdf'):
        os.replace('temp_t4_line.pdf', 'test4_success_vs_s.pdf')
    for ext in ['aux', 'log', 'tex']:
        if os.path.exists(f'temp_t4_line.{ext}'):
            os.remove(f'temp_t4_line.{ext}')
    print("Saved test4_success_vs_s.pdf and test4_success_vs_s.eps")


if __name__ == "__main__":
    import sys
    member_name = sys.argv[1] if len(sys.argv) > 1 else "results"
    print(f"Plotting results for member: {member_name}")
    plot_test1(member_name)
    plot_test2(member_name)
    plot_test3(member_name)
    plot_test4(member_name)
