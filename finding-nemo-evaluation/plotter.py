import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
import os
import glob

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

def plot_test1(member_name):
    files = glob.glob(f"{member_name}_test1.csv")
    if not files:
        print("No data for Test 1")
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
    plt.close()
    print("Saved test1_bootstrap.pdf")

def plot_test2(member_name):
    files = glob.glob(f"{member_name}_test2.csv")
    if not files:
        print("No data for Test 2")
        return
        
    df = pd.concat([pd.read_csv(f) for f in files])
    
    # 2A: Line chart % success vs g (for a fixed k, or averaged over k)
    fig, ax = plt.subplots(figsize=(6, 4))
    sns.lineplot(data=df, x='g', y='success_rate', hue='k', marker='s', ax=ax, palette='gray')
    ax.set_xlabel('Sequential Additions ($g$)')
    ax.set_ylabel('Success Rate (%)')
    ax.set_title('Test 2: Success Rate vs. Sequential Additions')
    ax.set_ylim(-5, 115)
    ax.grid(True, linestyle='--', alpha=0.7)
    plt.savefig('test2_success_vs_g.pdf')
    plt.close()
    print("Saved test2_success_vs_g.pdf")
    
    # 2B: Line chart % success vs k (averaged over g)
    fig, ax = plt.subplots(figsize=(6, 4))
    sns.lineplot(data=df, x='k', y='success_rate', marker='s', ax=ax, color='black')
    ax.set_xlabel('Target Degree Limit ($k$)')
    ax.set_ylabel('Success Rate (%)')
    ax.set_title('Test 2: Success Rate vs. $k$')
    ax.set_ylim(-5, 115)
    ax.grid(True, linestyle='--', alpha=0.7)
    plt.savefig('test2_success_vs_k.pdf')
    plt.close()
    print("Saved test2_success_vs_k.pdf")
    
    # 2C: Bubble chart mapping k vs g
    grouped = df.groupby(['k', 'g']).agg({'success_rate': 'mean', 'trials': 'sum'}).reset_index()
    
    fig, ax = plt.subplots(figsize=(6, 4))
    scatter = ax.scatter(grouped['k'], grouped['g'], s=grouped['trials']*10, 
                         c=grouped['success_rate'], cmap='gray', alpha=0.7, edgecolors='black')
    
    # Add labels
    for _, row in grouped.iterrows():
        ax.text(row['k'], row['g'], f"{row['success_rate']:.0f}%", 
                ha='center', va='center', fontsize=8, color='white' if row['success_rate'] < 50 else 'black')
                
    cbar = plt.colorbar(scatter, ax=ax)
    cbar.set_label('Success Rate (%)')
    
    ax.set_xlabel('Target Degree Limit ($k$)')
    ax.set_ylabel('Sequential Additions ($g$)')
    ax.set_title('Test 2: Bubble Chart of $k$ vs $g$ (Size = Trials)')
    plt.savefig('test2_bubble_k_vs_g.pdf')
    plt.close()
    print("Saved test2_bubble_k_vs_g.pdf")

def plot_test3(member_name):
    files = glob.glob(f"{member_name}_test3.csv")
    if not files:
        print("No data for Test 3")
        return
        
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
    plt.close()
    print("Saved test3_bubble_k_vs_s.pdf")

if __name__ == "__main__":
    import sys
    member_name = sys.argv[1] if len(sys.argv) > 1 else "results"
    print(f"Plotting results for member: {member_name}")
    plot_test1(member_name)
    plot_test2(member_name)
    plot_test3(member_name)
